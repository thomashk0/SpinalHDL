/*
 * SpinalHDL
 * Copyright (c) Dolu, All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */

package spinal.core

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 * Created by PIC18F on 11.01.2015.
 */


trait ConditionalContext extends GlobalDataUser{

  def push: Unit = {
    globalData.conditionalAssignStack.push(this)
  }

  def pop: Unit = {
    globalData.conditionalAssignStack.pop(this)
  }
}


object when {


  def doWhen(w: WhenContext, isTrue: Boolean)(block: => Unit): WhenContext = {
    w.isTrue = isTrue
    w.push
    block
    w.pop
    w
  }

  def apply(cond: Bool)(block: => Unit): WhenContext = {
    doWhen(new WhenContext(cond), true)(block)
  }

  //Allow the user to get a Bool that represent the  aggregation of all condition to the current context
  def getWhensCond(that: ContextUser): Bool = getWhensCond(if (that == null) null else that.conditionalAssignScope)


  def getWhensCond(scope: ConditionalContext): Bool = {
    var ret: Bool = null
    for (conditionalAssign <- GlobalData.get.conditionalAssignStack.stack) conditionalAssign match {
      case w : WhenContext => {
        if (w == scope) return returnFunc
        val newCond = if (w.isTrue) w.cond else !w.cond
        if (ret == null) {
          ret = newCond
        } else {
          ret = ret && newCond
        }
      }
    }


    def returnFunc = if (ret == null) True else ret

    returnFunc
  }
}

class WhenContext(val cond: Bool) extends ConditionalContext with ScalaLocated {
  var isTrue: Boolean = true;
  var parentElseWhen: WhenContext = null
  var childElseWhen: WhenContext = null

  def otherwise(block: => Unit): Unit = {
    restackElseWhen
    when.doWhen(this, false)(block)
    destackElseWhen
  }

  def elsewhen(cond: Bool)(block: => Unit) : WhenContext = {
    var w : WhenContext = null
    otherwise({
      w = when(cond) {
        block
      }
      w.parentElseWhen = this
      this.childElseWhen = w
    })
    w
  }

  def restackElseWhen: Unit = {
    if (parentElseWhen == null) return
    parentElseWhen.restackElseWhen
    parentElseWhen.push
  }

  def destackElseWhen: Unit = {
    if (parentElseWhen == null) return
    parentElseWhen.pop
    parentElseWhen.destackElseWhen
  }

}

class SwitchStack(val value: Data) {
  var lastWhen: WhenContext = null
  var defaultBlock: () => Unit = null
  val whenStackHead = GlobalData.get.conditionalAssignStack.head()
}


object WhenNode {

  def apply(forThat : BaseType,w: WhenContext): WhenNode = {
    apply(forThat,w, w.cond, null,null)
  }

  def apply(forThat : BaseType,w: WhenContext, cond: Bool, whenTrue: Node, whenFalse: Node): WhenNode = {
    val ret = newFor(forThat,w)
    ret.cond = cond
    ret.whenTrue = whenTrue.asInstanceOf[ret.T]
    ret.whenFalse = whenFalse.asInstanceOf[ret.T]
    ret
  }

  def newFor(that : BaseType,w: WhenContext) : WhenNode = that match{
    case that : BitVector => new WhenNodeWidthable(w)
    case that : SpinalEnumCraft[_] => new WhenNodeEnum(w,that.spinalEnum)
    case _ => new WhenNode(w)
  }
}

class WhenNode (val w: WhenContext) extends Node with AssignementTreePart  {
  type T <: Node
  var cond      : Node = null
  var whenTrue  : T = null.asInstanceOf[T]
  var whenFalse : T = null.asInstanceOf[T]

  override def addAttribute(attribute: Attribute): this.type = addTag(attribute)


  override def onEachInput(doThat: (Node, Int) => Unit): Unit = {
    doThat(cond,0)
    if(whenTrue != null)  doThat(whenTrue,1)
    if(whenFalse != null) doThat(whenFalse,2)
  }
  override def onEachInput(doThat: (Node) => Unit): Unit = {
    doThat(cond)
    if(whenTrue != null)  doThat(whenTrue)
    if(whenFalse != null) doThat(whenFalse)
  }

  override def setInput(id: Int, node: Node): Unit = id match{
    case 0 => cond = node
    case 1 => whenTrue = node.asInstanceOf[T]
    case 2 => whenFalse = node.asInstanceOf[T]
  }

  override def getInputsCount: Int = 1 + (if(whenTrue != null) 1 else 0) + (if(whenFalse != null) 1 else 0)
  override def getInputs: Iterator[Node] = (whenTrue != null,whenFalse != null) match{
    case (false,false) => Iterator(cond)
    case (false,true)  => Iterator(cond,whenFalse)
    case (true,false)  => Iterator(cond,whenTrue)
    case (true,true)   => Iterator(cond,whenTrue,whenFalse)
  }
  override def getInput(id: Int): Node = id match{
    case 0 => cond
    case 1 => whenTrue
    case 2 => whenFalse
  }

  var whenTrueThrowable : Throwable = null
  var whenFalseThrowable : Throwable = null

  override def getAssignementContext(id: Int): Throwable = id match{
    case 1 => whenTrueThrowable
    case 2 => whenFalseThrowable
  }
  override def setAssignementContext(id: Int,that : Throwable = globalData.getThrowable()): Unit =  id match{
    case 1 => whenTrueThrowable  = that
    case 2 => whenFalseThrowable = that
  }

  override private[core] def getOutToInUsage(inputId: Int, outHi: Int, outLo: Int): (Int, Int) = inputId match{
    case 0 => (0, 0)
    case _ => (outHi,outLo)
  }

  def cloneWhenNode : this.type = new WhenNode(w).asInstanceOf[this.type]
}

object AssignementTree{
  def getDrivedBaseType(that : Node): BaseType ={
    that match{
      case that : BaseType => that
      case _ => getDrivedBaseType(that.consumers.head)
    }
  }
}
class WhenNodeWidthable (w: WhenContext) extends WhenNode(w) with Widthable with CheckWidth{
  override type T = Node with WidthProvider

  override def calcWidth: Int = Math.max(if(whenTrue != null) whenTrue.getWidth else -1, if(whenFalse != null) whenFalse.getWidth else -1)

  override def normalizeInputs: Unit = {
    if(whenTrue != null)  InputNormalize.resizedOrUnfixedLit(this,1,this.getWidth)
    if(whenFalse != null) InputNormalize.resizedOrUnfixedLit(this,2,this.getWidth)
  }

  override private[core] def checkInferedWidth: Unit = {
    def doit(input : T,i : Int) : Unit = {
      if (input != null && input.component != null && this.getWidth != input.getWidth) {
        PendingError(s"Assignement bit count missmatch. ${AssignementTree.getDrivedBaseType(this)} := ${input}} at\n${ScalaLocated.long(getAssignementContext(i))}")
      }
    }

    doit(whenTrue,1);
    doit(whenFalse,2);
  }

  override def cloneWhenNode : this.type = new WhenNodeWidthable(w).asInstanceOf[this.type]
}

class WhenNodeEnum (w: WhenContext,enumDef : SpinalEnum) extends WhenNode(w) with InferableEnumEncodingImpl{
  override type T = Node with EnumEncoded
  override private[core] def getDefaultEncoding(): SpinalEnumEncoding = enumDef.defaultEncoding
  override def getDefinition: SpinalEnum = enumDef
  override private[core] def normalizeInputs: Unit = {
    InputNormalize.enumImpl(this)
  }
  override def cloneWhenNode : this.type = new WhenNodeEnum(w,enumDef).asInstanceOf[this.type]
}


class SwitchContext(val value: Data) extends ConditionalContext{
  var defaultBlockPresent = false
  val conditionalAssignStackHead = GlobalData.get.conditionalAssignStack.head()
  val defaultCond = True
  var caseCount = 0
  var defaultContext : CaseContext = null
}

class CaseContext(val switchContext: SwitchContext,val cond : Bool) extends ConditionalContext{
  val id = switchContext.caseCount
  switchContext.caseCount += 1
}



object switch {
  def apply[T <: Data](value: T)(block: => Unit): Unit = {
    //value.globalData.pushNetlistLock(() => {
//      SpinalError(s"You should not use 'general statments' in the 'switch' scope, but only 'is' statments.\n${ScalaLocated.long}")
//    })
    val s = new SwitchStack(value)
    value.globalData.switchStack.push(s)
    block

    //value.globalData.pushNetlistUnlock()
    if (s.defaultBlock != null) {
      if (s.lastWhen == null) {
        block
      } else {
        s.lastWhen.otherwise(s.defaultBlock())
      }
    }

    //value.globalData.popNetlistUnlock()
    value.globalData.switchStack.pop(s)
    //value.globalData.popNetlistLock
  }
}


object is {
  def apply(values:  Any*)(block: => Unit): Unit = list(values.iterator)(block)

  def list(values: Iterator[Any])(block: => Unit): Unit = {
    val globalData = GlobalData.get
    if (globalData.switchStack.isEmpty) SpinalError("Use 'is' statement outside the 'switch'")
    globalData.pushNetlistUnlock()

    val value = globalData.switchStack.head()
    if (value.whenStackHead != globalData.conditionalAssignStack.head())
      SpinalError("'is' statement is not at the top level of the 'switch'")
    val e = ArrayBuffer[Bool]()
    val switchValue = value.value

    def analyse(key: Any): Bool = {
      key match {
        case key: Data => switchValue.isEquals(key)
        case key: Seq[_] => key.map(d => analyse(d)).reduce(_ || _)
        case key: Int => {
          switchValue match {
            case switchValue: Bits => switchValue === B(key)
            case switchValue: UInt => switchValue === U(key)
            case switchValue: SInt => switchValue === S(key)
            case _ => SpinalError("The switch is not a Bits, UInt or SInt")
          }
        }
        case key: BigInt => {
          switchValue match {
            case switchValue: Bits => switchValue === B(key)
            case switchValue: UInt => switchValue === U(key)
            case switchValue: SInt => switchValue === S(key)
            case _ => SpinalError("The switch is not a Bits, UInt or SInt")
          }
        }
        case that : SpinalEnumElement[_] => switchValue.isEquals(that())
        case key : MaskedLiteral => switchValue match {
          case switchValue: Bits => switchValue === key
          case switchValue: UInt => switchValue === key
          case switchValue: SInt => switchValue === key
          case _ => SpinalError("The switch is not a Bits, UInt or SInt")
        }
      }
    }


    val cond = values.map(analyse(_)).reduce(_ || _)
    if (value.lastWhen == null) {
      value.lastWhen = when(cond)(block)
    } else {
      value.lastWhen = value.lastWhen.elsewhen(cond)(block)
    }
    globalData.popNetlistUnlock()
  }
}

object default {
  def apply(block: => Unit): Unit = {
    val globalData = GlobalData.get
    if (globalData.switchStack.isEmpty) SpinalError("Use 'default' statement outside the 'switch'")
    globalData.pushNetlistUnlock()
    val value = globalData.switchStack.head()

    if (value.whenStackHead != globalData.conditionalAssignStack.head()) SpinalError("'default' statement is not at the top level of the 'switch'")
    if (value.defaultBlock != null) SpinalError("'default' statement must appear only one time in the 'switch'")
    value.defaultBlock = () => {
      block
    }
    globalData.popNetlistUnlock()
  }
}
