package com.kotori316.fluidtank.transport

import cats.Eval
import cats.data.OptionT
import cats.implicits._
import com.kotori316.fluidtank.tiles.Tiers
import com.kotori316.fluidtank.transport.PipeConnection._
import com.kotori316.fluidtank.{FluidTank, ModObjects, _}
import net.minecraft.tileentity.{ITickableTileEntity, TileEntity}
import net.minecraft.util.Direction
import net.minecraft.util.math.BlockPos
import net.minecraftforge.fluids.FluidUtil
import net.minecraftforge.fluids.capability.CapabilityFluidHandler

class PipeTile extends TileEntity(ModObjects.PIPE_TYPE) with ITickableTileEntity {
  var connection: PipeConnection[BlockPos] = getEmptyConnection

  private def getEmptyConnection: PipeConnection[BlockPos] = PipeConnection.empty({ case (p, c) =>
    getWorld.getTileEntity(p) match {
      case pipeTile: PipeTile => pipeTile.connection = c
      case _ =>
    }
  }, p =>
    getWorld.getBlockState(p) match {
      case s if s.getBlock == ModObjects.blockPipe =>
        PipeBlock.FACING_TO_PROPERTY_MAP.values().stream().anyMatch(pr => s.get(pr).isOutput)
      case _ => false
    }
  )

  override def tick(): Unit = {
    if (!world.isRemote) {
      if (connection.isEmpty)
        makeConnection()
      import scala.jdk.CollectionConverters._
      PipeBlock.FACING_TO_PROPERTY_MAP.asScala.toSeq.flatMap { case (direction, value) =>
        if (getBlockState.get(value).isInput) {
          val sourcePos = pos.offset(direction)
          val c = for {
            t <- OptionT.fromOption[Eval](Option(getWorld.getTileEntity(sourcePos)))
            cap <- t.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, direction.getOpposite).asScala
          } yield cap -> sourcePos
          c.toList
        } else {
          List.empty
        }
      }.foreach { case (f, sourcePos) =>
        for {
          p <- connection.outputs
          (direction, pos) <- Direction.values().map(f => f -> p.offset(f))
          if pos != sourcePos
          if getWorld.getBlockState(p).get(PipeBlock.FACING_TO_PROPERTY_MAP.get(direction)).isOutput
          dest <- OptionT.fromOption[Eval](Option(getWorld.getTileEntity(pos)))
            .flatMap(_.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, direction.getOpposite).asScala)
            .toList
          if f != dest
        } {
          val transferSimulate = FluidUtil.tryFluidTransfer(dest, f, PipeTile.amountPerTick, false)
          if (!transferSimulate.isEmpty) {
            FluidUtil.tryFluidTransfer(dest, f, transferSimulate, true)
          }
        }
      }
    }
  }

  def makeConnection(): Unit = {
    val facings = Direction.values().toList
    val checked = scala.collection.mutable.Set.empty[BlockPos]

    def makePosList(start: BlockPos): List[BlockPos] = {
      for {
        d <- facings
        pos <- start.offset(d).pure[List]
        if checked.add(pos) // True means it's first time to check the pos. False means the pos already checked.
        state <- getWorld.getBlockState(pos).pure[List]
        if state.getBlock == ModObjects.blockPipe
        if state.get(PipeBlock.FACING_TO_PROPERTY_MAP.get(d.getOpposite)) == PipeBlock.Connection.CONNECTED
        pos2 <- pos :: makePosList(pos)
      } yield pos2
    }

    val poses: List[BlockPos] = makePosList(getPos)
    val lastConnection = if (poses.isEmpty) getEmptyConnection.add(getPos) else poses.foldl(getEmptyConnection) { case (c, p) => c add p }
    FluidTank.LOGGER.debug(s"PipeConnection, fromPos: $pos, made: $lastConnection")
  }

  def connectorUpdate(): Unit = {

  }
}

object PipeTile {
  final val amountPerTick = Utils.toInt(Tiers.WOOD.amount)
}