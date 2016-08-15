package spinal.lib.bus.amba4.axi

import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.SizeMapping

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

case class Axi4InterconnectSlaveConnection(master : Axi4/*,priority : Int*/)
case class Axi4InterconnectSlaveConfig(mapping : SizeMapping){
  val connections = ArrayBuffer[Axi4InterconnectSlaveConnection]()
}


case class Axi4InterconnectFactory(/*axiConfig: Axi4Config*/){
  val slavesConfigs = mutable.HashMap[Axi4,Axi4InterconnectSlaveConfig]()

  def addSlave(ahb: Axi4,mapping: SizeMapping) : this.type = {
    slavesConfigs(ahb) = Axi4InterconnectSlaveConfig(mapping)
    this
  }

  def addSlaves(orders : (Axi4,SizeMapping)*) : this.type = {
    orders.foreach(order => addSlave(order._1,order._2))
    this
  }

  def addConnection(ahb: Axi4,Axi4Slave: Iterable[Axi4]) : this.type = {
    Axi4Slave.foreach(slavesConfigs(_).connections += Axi4InterconnectSlaveConnection(ahb))
    this
  }


  def addConnection(order: (Axi4,Iterable[Axi4])) : this.type = addConnection(order._1,order._2)

  def addConnections(orders : (Axi4,Iterable[Axi4])*) : this.type = {
    orders.foreach(addConnection(_))
    this
  }

  def buildRead() = new Area{
    val masters = slavesConfigs.values.map(_.connections.map(_.master)).flatten.toSet
    val masterToDecodedSlave = mutable.HashMap[Axi4,Map[Axi4,Axi4ReadOnly]]()
    val decoders = for(master <- masters) yield new Area{
      val slaves = slavesConfigs.filter{
        case (slave,config) => config.connections.exists(connection => connection.master == master)
      }
      val decoder = Axi4ReadOnlyDecoder(
        axiConfig = master.config,
        decodings = slaves.map(_._2.mapping)
      )

      masterToDecodedSlave(master) = (slaves.map(_._1),decoder.io.outputs).zipped.toMap
      decoder.io.input << master.toReadOnly

      decoder.setPartialName(master,"readDecoder")
    }

    val arbiters = for((slave,config) <- slavesConfigs) yield {
      val readConnections = config.connections
      readConnections.size match {
        case 0 => PendingError(s"$slave has no master}")
        case 1 => slave << readConnections.head.master
        case _ => new Area {
          val arbiter = Axi4ReadArbiter(
            outputConfig = slave.config,
            inputsCount = readConnections.length
          )
          for ((input, master) <- (arbiter.io.inputs, readConnections).zipped) {
            input << masterToDecodedSlave(master.master)(slave)
          }
          arbiter.io.output >> slave
          arbiter.setPartialName(slave, "readArbiter")
        }
      }
    }
  }

  def buildWrite() = new Area{
    val masters = slavesConfigs.values.map(_.connections.map(_.master)).flatten.toSet
    val masterToDecodedSlave = mutable.HashMap[Axi4,Map[Axi4,Axi4WriteOnly]]()
    val decoders = for(master <- masters) yield new Area{
      val slaves = slavesConfigs.filter{
        case (slave,config) => config.connections.exists(connection => connection.master == master)
      }
      val decoder = Axi4WriteDecoder(
        axiConfig = master.config,
        decodings = slaves.map(_._2.mapping)
      )

      masterToDecodedSlave(master) = (slaves.map(_._1),decoder.io.outputs).zipped.toMap
      decoder.io.input << master.toWriteOnly

      decoder.setPartialName(master,"writeDecoder")
    }

    val arbiters = for((slave,config) <- slavesConfigs) yield  {
      val writeConnections = config.connections
      config.connections.size match {
        case 0 => PendingError(s"$slave has no master}")
        case 1 => slave << writeConnections.head.master
        case _ => new Area {
          val arbiter = Axi4WriteArbiter(
            outputConfig = slave.config,
            inputsCount = writeConnections.length,
            routeBufferSize = 4
          )
          for ((input, master) <- (arbiter.io.inputs, writeConnections).zipped) {
            input << masterToDecodedSlave(master.master)(slave)
          }
          arbiter.io.output >> slave
          arbiter.setPartialName(slave, "writeArbiter")
        }
      }
    }
  }

  def build(): Unit ={
    buildRead()
    buildWrite()
  }
}