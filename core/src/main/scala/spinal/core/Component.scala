/*                                                                           *\
**        _____ ____  _____   _____    __                                    **
**       / ___// __ \/  _/ | / /   |  / /   HDL Core                         **
**       \__ \/ /_/ // //  |/ / /| | / /    (c) Dolu, All rights reserved    **
**      ___/ / ____// // /|  / ___ |/ /___                                   **
**     /____/_/   /___/_/ |_/_/  |_/_____/                                   **
**                                                                           **
**      This library is free software; you can redistribute it and/or        **
**    modify it under the terms of the GNU Lesser General Public             **
**    License as published by the Free Software Foundation; either           **
**    version 3.0 of the License, or (at your option) any later version.     **
**                                                                           **
**      This library is distributed in the hope that it will be useful,      **
**    but WITHOUT ANY WARRANTY; without even the implied warranty of         **
**    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU      **
**    Lesser General Public License for more details.                        **
**                                                                           **
**      You should have received a copy of the GNU Lesser General Public     **
**    License along with this library.                                       **
\*                                                                           */

package spinal.core

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
  * Component companion
  */
object Component {

  /**
    * Push a new component on the stack
    * @param c new component to add
    */
  def push(c: Component): Unit = {
    //  if (when.stack.size() != 0) throw new Exception("Creating a component into hardware conditional expression")
    GlobalData.get.componentStack.push(c)
  }

  /**
    * Remove component of the stack if it is the same as c
    * @param c component to remove
    */
  def pop(c: Component): Unit = {
    GlobalData.get.componentStack.pop(c)
  }

  /** Get the current component on the stack */
  def current: Component = current(GlobalData.get)

  /** Get the current component on the stack of the given globalData*/
  def current(globalData: GlobalData): Component = globalData.componentStack.head()
}


/**
  * Abstract class used to create a new Component
  *
  * @example {{{
  *         class MyAndGate extends Component {
  *           val io = new Bundle{
  *             val a,b = in Bool
  *             val res = out Bool
  *           }
  *           io.res := io.a & io.b
  *         }
  * }}}
  *
  * @see  [[http://spinalhdl.github.io/SpinalDoc/spinal/core/components_hierarchy Component Documentation]]
  */
abstract class Component extends NameableByComponent with GlobalDataUser with ScalaLocated with DelayedInit with Stackable with OwnableRef{

  /** Class used to create a task that must be executed after the creation of the component */
  case class PrePopTask(task : () => Unit, clockDomain: ClockDomain)

  /** Array of PrePopTask */
  private[core] var prePopTasks = mutable.ArrayBuffer[PrePopTask]()
  /** Contains all in/out signals of the component */
  private[core] val ioSet = mutable.Set[BaseType]()
  /** enable/disable "io_" prefix in front of the in/out signals in the RTL */
  private[core] var ioPrefixEnable = true
  /** Used to store arbitrary object related to the component */
  val userCache = mutable.Map[Object, mutable.Map[Object, Object]]()
  private[core] val kindsOutputsToBindings = mutable.Map[BaseType, BaseType]()
  private[core] val kindsOutputsBindings = mutable.Set[BaseType]()
  private[core] val additionalNodesRoot = mutable.Set[Node]()
  def getAdditionalNodesRoot : mutable.Set[Node] = additionalNodesRoot

  /** Definition Name (name of the entity (VHDL) or module (Verilog))*/
  var definitionName: String = null
  /** Hierarchy level of the component */
  private[core] val level = globalData.componentStack.size()
  /** Contains an array of all children Component */
  val children = ArrayBuffer[Component]()
  /** Reference owner type */
  override type RefOwnerType = Component
  /** Contains all nodes of the components */
  var nodes: ArrayBuffer[Node] = null

  private[core] var pulledDataCache = mutable.Map[Data, Data]()
  private[core] val initialAssignementCondition = globalData.conditionalAssignStack.head()

  /** Get the parent component (null if there is no parent)*/
  val parent = Component.current
  /** Get the current clock domain (null if there is no clock domain already set )*/
  val clockDomain = ClockDomain.current


  // Check if it is a top level component or a children of another component
  if (parent != null) {
    parent.children += this
  } else {
    setWeakName("toplevel")
  }

  // Push component on the stack
  Component.push(this)


  def prePop(): Unit ={
    while(prePopTasks.nonEmpty){
      val prePopTasksToDo = prePopTasks
      prePopTasks = mutable.ArrayBuffer[PrePopTask]()
      for(t <- prePopTasksToDo){
        t.clockDomain(t.task())
      }
    }
  }
  /** Initialization class delay */
  override def delayedInit(body: => Unit) = {
    body // evaluate the initialization code of body

    // prePopTasks are executed after the creation of the inherited component
    if ((body _).getClass.getDeclaringClass == this.getClass) {

      this.nameElements()

      prePop()

      Component.pop(this)
    }
  }

  /** Add a new prePopTask */
  def addPrePopTask(task : () => Unit) = prePopTasks += PrePopTask(task, ClockDomain.current)

  /** Set the definition name of the component */
  def setDefinitionName(name: String): this.type = {
    definitionName = name
    this
  }

  /** No "io_" prefix in front of the in/out signals */
  def noIoPrefix() : this.type = {
    val io = reflectIo
    if(io != null) {
      io.setName("")
    }
    ioPrefixEnable = false
    this
  }

  /** Check if this component is the top level */
  private[core] def isTopLevel: Boolean = parent == null

  /** Return a list of all parents of the components */
  def parents(of: Component = this, list: List[Component] = Nil): List[Component] = {
    if (of.parent == null) return list
    parents(of.parent, of.parent :: list)
  }

  /**
    * Get the IO definition of the component
    * @example {{{ val io = new Bundle { ... } }}}
    */
  private[core] def reflectIo: Data = {
    try {
      val clazz = this.getClass
      val m = clazz.getMethod("io")
      m.invoke(this).asInstanceOf[Data]
    } catch {
      case _: Throwable => null
    }
  }

  /** Name all Nameable object */
  def nameElements(): Unit = {
    val io = reflectIo

    if(io != null) {
      if(io.isUnnamed || io.isWeak) {
        if (ioPrefixEnable)
          io.setName("io")
        else
          io.setName("")
      }
      OwnableRef.proposal(io,this)
    }

    Misc.reflect(this, (name, obj) => {
      if(obj != io) {
        obj match {
          case component: Component if (component.parent == this) =>
              OwnableRef.proposal(obj, this)
              component.setWeakName(name)

          case nameable: Nameable =>
            if (!nameable.isInstanceOf[ContextUser]) {
              nameable.setWeakName(name)
              OwnableRef.proposal(obj, this)
            } else if (nameable.asInstanceOf[ContextUser].component == this) {
              nameable.setWeakName(name)
              OwnableRef.proposal(obj, this)
            } else {
              for (kind <- children) {
                //Allow to name a component by his io reference into the parent component
                if (kind.reflectIo == nameable) {
                  kind.setWeakName(name)
                  OwnableRef.proposal(kind, this)
                }
              }
            }
          case _ =>
        }
      }
    })
  }

  /**
    * Name allocation
    */
  private[core] def allocateNames(globalScope : Scope): Unit = {
    val localScope = globalScope.newChild
    localScope.allocateName(globalData.anonymSignalPrefix)

    for (node <- nodes) node match {
      case nameable: Nameable =>
        if (nameable.isUnnamed || nameable.getName() == "") {
          nameable.unsetName()
          nameable.setWeakName(globalData.anonymSignalPrefix)
        }
        if (nameable.isWeak)
          nameable.setName(localScope.allocateName(nameable.getName()))
        else
          localScope.iWantIt(nameable.getName(),s"Reserved name ${nameable.getName()} is not free for ${nameable.toString()}")
      case _ =>
    }

    for (child <- children) {
      OwnableRef.proposal(child,this)
      if (child.isUnnamed) {
        var name = child.getClass.getSimpleName
        name = Character.toLowerCase(name.charAt(0)) + (if (name.length() > 1) name.substring(1) else "")
        child.unsetName()
        child.setWeakName(name)
      }
      child.setName(localScope.allocateName(child.getName()))
    }
  }

  /** Get a set of all IO available in the component */
  def getAllIo: mutable.Set[BaseType] = {

    if (nodes == null) {
      ioSet
    } else {
      val nodeIo = mutable.Set[BaseType]()
      nodes.foreach {
        case b: BaseType if (b.isIo) => nodeIo += b
        case _ =>
      }
      nodeIo
    }

  }

  /** Sort all IO regarding instanceCounter */
  def getOrdredNodeIo = getAllIo.toList.sortWith(_.instanceCounter < _.instanceCounter)


  /** Get an array of all SyncNode of the Component */
  private[core] def getDelays = {
    val delays = new ArrayBuffer[SyncNode]()

    nodes.foreach {
      case delay: SyncNode => delays += delay
      case _ =>
    }

    delays
  }

  private[core] def userParentCalledDef: Unit = {}

  private[core] def isInBlackBoxTree: Boolean = if (parent == null) false else parent.isInBlackBoxTree

  private[core] override def getComponent(): Component = parent

  override def getDisplayName(): String = if (isNamed) super.getDisplayName() else "[" + getClass.getSimpleName + "]"

  /**
    * Return the path of the parent
    *
    * @example{{{ toplevel/[myComponent1] // Current component is myComponent2 }}}
    */
  def getParentsPath(sep: String = "/"): String = if (parent == null) "" else parents().map(_.getDisplayName()).reduce(_ + sep + _)

  /**
    * Return the path of the component
    *
    * @example{{{ toplevel/[myComponent1]/[myComponent2] // Current component is myComponent2 }}}
    */
  def getPath(sep: String = "/"): String = (if (parent == null) "" else (getParentsPath(sep) + sep)) + this.getDisplayName()


  def getGroupedIO(ioBundleBypass: Boolean): Seq[Data] = {
    val ret = mutable.Set[Data]()
    val ioBundle = if (ioBundleBypass) reflectIo else null
    def getRootParent(that: Data): Data = if (that.parent == null || that.parent == ioBundle) that else getRootParent(that.parent)
    for (e <- getOrdredNodeIo) {
      ret += getRootParent(e)
    }
    ret.toSeq.sortBy(_.instanceCounter)
  }

  override def postPushEvent(): Unit = {}
  override def prePopEvent(): Unit = {}


  /** Rework the component */
  def rework[T](gen: => T) : T = {
    Component.push(this)
    val ret = gen
    prePop()
    Component.pop(this)
    ret
  }
}


