// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// License: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.server.protocol.swank

import scala.util._

import org.ensime.api._
import org.ensime.sexp._
import org.ensime.sexp.formats._
import org.ensime.util.ensimefile._
import shapeless.cachedImplicit

/*
 * WARNING: THERE BE DRAGONS
 *
 * This file exists to define the custom marshalling of the domain
 * objects into the legacy ENSIME SWANK protocol (v0.9/1.0).
 *
 * We could reduce the boilerplate of this file by using shapeless,
 * making it look more like the minimal JerkFormats, but it seems
 * somewhat futile to go to such efforts before simplifying the
 * protocol in 2.0.
 *
 * https://github.com/ensime/ensime-server/issues/834
 */

final case class SwankRPCFormatException(msg: String, callId: Int, cause: Throwable = null) extends Exception(msg, cause)

object SwankProtocolConversions extends DefaultSexpProtocol
    with SymbolAltFormat
    with OptionAltFormat
    with LegacyFamilyFormats
    with CamelCaseToDashes {
  override def skipNilValues: Boolean = true
}

object SwankProtocolCommon {
  import SwankProtocolConversions._

  /**
   * Alternative form for family formats that serialises the typehint
   * as a field value on the same level as the other parts (assumed to
   * be a SexpData), i.e. to match our legacy format.
   */
  abstract class TraitFormatAlt[T] extends SexpFormat[T] {
    val key = SexpSymbol(":type")
    protected def wrap[E](t: E)(
      implicit
      th: TypeHint[E], sf: SexpFormat[E]
    ): Sexp = t.toSexp match {
      case SexpNil => SexpData(key -> th.hint)
      case SexpData(data) if !data.contains(key) =>
        SexpData(key -> th.hint :: data.toList)
      case SexpList(Nil) =>
        // special case: no param case classes
        SexpData(key -> th.hint)
      case other =>
        serializationError(s"expected ${th.hint}'s wrap to be SexpData, was $other")
    }
    final def read(sexp: Sexp): T = sexp match {
      case SexpData(map) if map.contains(key) =>
        map(key) match {
          case hint: SexpSymbol => read(hint, SexpData((map - key).toList))
          case not => deserializationError(not)
        }
      case x => deserializationError(x)
    }
    protected def read(hint: SexpSymbol, value: Sexp): T
  }

  implicit object EnsimeFileFormat extends SexpFormat[EnsimeFile] {
    def write(ef: EnsimeFile): Sexp = ef match {
      case RawFile(path) => SexpString(path.toString)
      case a: ArchiveFile => SexpString(a.uri.toASCIIString)
    }
    def read(sexp: Sexp): EnsimeFile = sexp match {
      case SexpString(uri) => EnsimeFile(uri)
      case got => deserializationError(got)
    }
  }

  implicit val SourceFileInfoFormat: SexpFormat[SourceFileInfo] = cachedImplicit

  private val sourceSymbolMap = Map(
    "object" -> ObjectSymbol,
    "class" -> ClassSymbol,
    "trait" -> TraitSymbol,
    "package" -> PackageSymbol,
    "constructor" -> ConstructorSymbol,
    "importedName" -> ImportedNameSymbol,
    "typeParam" -> TypeParamSymbol,
    "param" -> ParamSymbol,
    "varField" -> VarFieldSymbol,
    "valField" -> ValFieldSymbol,
    "operator" -> OperatorFieldSymbol,
    "var" -> VarSymbol,
    "val" -> ValSymbol,
    "functionCall" -> FunctionCallSymbol,
    "implicitConversion" -> ImplicitConversionSymbol,
    "implicitParams" -> ImplicitParamsSymbol,
    "deprecated" -> DeprecatedSymbol
  )
  private val reverseSourceSymbolMap: Map[SourceSymbol, String] =
    sourceSymbolMap.map { case (name, symbol) => symbol -> name }

  private[swank] def symbolToSourceSymbol(stringRep: String): Option[SourceSymbol] = sourceSymbolMap.get(stringRep)
  private[swank] def sourceSymbolToSymbol(sym: SourceSymbol): String = reverseSourceSymbolMap.get(sym).get

  implicit object SourceSymbolFormat extends SexpFormat[SourceSymbol] {
    def write(sym: SourceSymbol): Sexp = SexpSymbol(sourceSymbolToSymbol(sym))
    def read(sexp: Sexp): SourceSymbol = sexp match {
      case SexpSymbol(name) if sourceSymbolMap.contains(name) =>
        sourceSymbolMap(name)
      case _ => deserializationError(sexp)
    }
  }

  implicit object RefactorTypeFormat extends SexpFormat[RefactorType] {
    def write(tpe: RefactorType): Sexp = SexpSymbol(tpe.symbol.name)
    def read(sexp: Sexp): RefactorType = sexp match {
      case SexpSymbol(name) =>
        RefactorType.allTypes.find(_.symbol.name == name).getOrElse(deserializationError(sexp))
      case _ =>
        deserializationError(sexp)
    }
  }

  implicit object DeclaredAsFormat extends SexpFormat[DeclaredAs] {
    def write(decl: DeclaredAs): Sexp = SexpSymbol(decl.symbol.name)
    def read(sexp: Sexp): DeclaredAs = sexp match {
      case SexpSymbol(name) =>
        DeclaredAs.allDeclarations.find(_.symbol.name == name).getOrElse(deserializationError(sexp))
      case _ =>
        deserializationError(sexp)
    }
  }

  implicit val DebugThreadIdFormat: SexpFormat[DebugThreadId] = viaString(new ViaString[DebugThreadId] {
    def toSexpString(id: DebugThreadId) = id.id.toString
    def fromSexpString(s: String) = DebugThreadId(s)
  })

  implicit val DebugObjectIdFormat: SexpFormat[DebugObjectId] = viaString(new ViaString[DebugObjectId] {
    def toSexpString(id: DebugObjectId) = id.id.toString
    def fromSexpString(s: String) = DebugObjectId(s)
  })

  implicit val DebugObjectReferenceHint: TypeHint[DebugObjectReference] = TypeHint[DebugObjectReference](SexpSymbol("reference"))
  implicit val DebugArrayElementHint: TypeHint[DebugArrayElement] = TypeHint[DebugArrayElement](SexpSymbol("element"))
  implicit val DebugObjectFieldHint: TypeHint[DebugObjectField] = TypeHint[DebugObjectField](SexpSymbol("field"))
  implicit val DebugStackSlotHint: TypeHint[DebugStackSlot] = TypeHint[DebugStackSlot](SexpSymbol("slot"))

  implicit val DebugObjectReferenceFormat: SexpFormat[DebugObjectReference] = cachedImplicit
  implicit val DebugArrayElementFormat: SexpFormat[DebugArrayElement] = cachedImplicit
  implicit val DebugObjectFieldFormat: SexpFormat[DebugObjectField] = cachedImplicit
  implicit val DebugStackSlotFormat: SexpFormat[DebugStackSlot] = cachedImplicit

  implicit object DebugLocationFormat extends TraitFormatAlt[DebugLocation] {
    def write(dl: DebugLocation): Sexp = dl match {
      case dor: DebugObjectReference => wrap(dor)
      case dae: DebugArrayElement => wrap(dae)
      case dof: DebugObjectField => wrap(dof)
      case dss: DebugStackSlot => wrap(dss)
    }
    def read(hint: SexpSymbol, value: Sexp): DebugLocation = hint match {
      case s if s == DebugObjectReferenceHint.hint =>
        value.convertTo[DebugObjectReference]
      case s if s == DebugArrayElementHint.hint =>
        value.convertTo[DebugArrayElement]
      case s if s == DebugObjectFieldHint.hint =>
        value.convertTo[DebugObjectField]
      case s if s == DebugStackSlotHint.hint =>
        value.convertTo[DebugStackSlot]
      case _ => deserializationError(hint)
    }
  }

}

object SwankProtocolResponse {
  import SwankProtocolConversions._
  import SwankProtocolCommon._

  /**
   * By default, S-Express uses the simple name of a class as the
   * typehint when resolving implementations of a sealed trait.
   * However, the ENSIME protocol uses custom typehints, which are
   * defined here - in combination with trait-specific typehint rules.
   */
  implicit val DebugPrimitiveHint: TypeHint[DebugPrimitiveValue] = TypeHint[DebugPrimitiveValue](SexpSymbol("prim"))
  implicit val DebugObjectHint: TypeHint[DebugObjectInstance] = TypeHint[DebugObjectInstance](SexpSymbol("obj"))
  implicit val DebugArrayHint: TypeHint[DebugArrayInstance] = TypeHint[DebugArrayInstance](SexpSymbol("arr"))
  implicit val DebugStringHint: TypeHint[DebugStringInstance] = TypeHint[DebugStringInstance](SexpSymbol("str"))
  implicit val DebugNullHint: TypeHint[DebugNullValue] = TypeHint[DebugNullValue](SexpSymbol("null"))
  implicit val NoteErrorHint: TypeHint[NoteError.type] = TypeHint[NoteError.type](SexpSymbol("error"))
  implicit val NoteWarnHint: TypeHint[NoteWarn.type] = TypeHint[NoteWarn.type](SexpSymbol("warn"))
  implicit val NoteInfoHint: TypeHint[NoteInfo.type] = TypeHint[NoteInfo.type](SexpSymbol("info"))
  implicit val DebugStepHint: TypeHint[DebugStepEvent] = TypeHint[DebugStepEvent](SexpSymbol("step"))
  implicit val DebugBreakHint: TypeHint[DebugBreakEvent] = TypeHint[DebugBreakEvent](SexpSymbol("breakpoint"))
  implicit val DebugVMStartHint: TypeHint[DebugVmStartEvent.type] = TypeHint[DebugVmStartEvent.type](SexpSymbol("start"))
  implicit val DebugVMDisconnectHint: TypeHint[DebugVmDisconnectEvent.type] = TypeHint[DebugVmDisconnectEvent.type](SexpSymbol("disconnect"))
  implicit val DebugExceptionHint: TypeHint[DebugExceptionEvent] = TypeHint[DebugExceptionEvent](SexpSymbol("exception"))
  implicit val DebugThreadStartHint: TypeHint[DebugThreadStartEvent] = TypeHint[DebugThreadStartEvent](SexpSymbol("threadStart"))
  implicit val DebugThreadDeathHint: TypeHint[DebugThreadDeathEvent] = TypeHint[DebugThreadDeathEvent](SexpSymbol("threadDeath"))
  implicit val DebugOutputHint: TypeHint[DebugOutputEvent] = TypeHint[DebugOutputEvent](SexpSymbol("output"))
  implicit val AnalyzerReadyHint: TypeHint[AnalyzerReadyEvent.type] = TypeHint[AnalyzerReadyEvent.type](SexpSymbol(":compiler-ready"))
  implicit val FullTypeCheckCompleteHint: TypeHint[FullTypeCheckCompleteEvent.type] = TypeHint[FullTypeCheckCompleteEvent.type](SexpSymbol(":full-typecheck-finished"))
  implicit val IndexerReadyHint: TypeHint[IndexerReadyEvent.type] = TypeHint[IndexerReadyEvent.type](SexpSymbol(":indexer-ready"))
  implicit val CompilerRestartedHint: TypeHint[CompilerRestartedEvent.type] = TypeHint[CompilerRestartedEvent.type](SexpSymbol(":compiler-restarted"))
  implicit val NewScalaNotesHint: TypeHint[NewScalaNotesEvent] = TypeHint[NewScalaNotesEvent](SexpSymbol(":scala-notes"))
  implicit val ClearAllScalaNotesHint: TypeHint[ClearAllScalaNotesEvent.type] = TypeHint[ClearAllScalaNotesEvent.type](SexpSymbol(":clear-all-scala-notes"))
  implicit val NewJavaNotesHint: TypeHint[NewJavaNotesEvent] = TypeHint[NewJavaNotesEvent](SexpSymbol(":java-notes"))
  implicit val ClearAllJavaNotesHint: TypeHint[ClearAllJavaNotesEvent.type] = TypeHint[ClearAllJavaNotesEvent.type](SexpSymbol(":clear-all-java-notes"))
  implicit val SendBackgroundMessageHint: TypeHint[SendBackgroundMessageEvent] = TypeHint[SendBackgroundMessageEvent](SexpSymbol(":background-message"))
  implicit val DebugHint: TypeHint[DebugEvent] = TypeHint[DebugEvent](SexpSymbol(":debug-event"))
  implicit val NamedTypeMemberHint: TypeHint[NamedTypeMemberInfo] = TypeHint[NamedTypeMemberInfo](SexpSymbol("named"))
  implicit val PackageHint: TypeHint[PackageInfo] = TypeHint[PackageInfo](SexpSymbol("package"))
  implicit val TypeInfoHint: TypeHint[TypeInfo] = TypeHint[TypeInfo](SexpSymbol("type"))
  implicit val ArrowTypeHint: TypeHint[ArrowTypeInfo] = TypeHint[ArrowTypeInfo](SexpSymbol("t"))
  implicit val BasicTypeHint: TypeHint[BasicTypeInfo] = TypeHint[BasicTypeInfo](SexpSymbol("nil")) // can't be SexpNil because it's not a Symbol
  implicit val DebugVmSuccessHint: TypeHint[DebugVmSuccess] = TypeHint[DebugVmSuccess](SexpSymbol("success"))
  implicit val DebugVmErrorHint: TypeHint[DebugVmError] = TypeHint[DebugVmError](SexpSymbol("error"))
  implicit val MethodSearchResultHint: TypeHint[MethodSearchResult] = TypeHint[MethodSearchResult](SexpSymbol("method"))
  implicit val TypeSearchResultHint: TypeHint[TypeSearchResult] = TypeHint[TypeSearchResult](SexpSymbol("type"))

  implicit val EmptySourcePositionHint: TypeHint[EmptySourcePosition] = TypeHint[EmptySourcePosition](SexpSymbol("empty"))
  implicit val LineSourcePositionHint: TypeHint[LineSourcePosition] = TypeHint[LineSourcePosition](SexpSymbol("line"))
  implicit val OffsetSourcePositionHint: TypeHint[OffsetSourcePosition] = TypeHint[OffsetSourcePosition](SexpSymbol("offset"))
  implicit val TextEditHint: TypeHint[TextEdit] = TypeHint[TextEdit](SexpSymbol("edit"))
  implicit val DeleteFileHint: TypeHint[DeleteFile] = TypeHint[DeleteFile](SexpSymbol("delete"))
  implicit val NewFileHint: TypeHint[NewFile] = TypeHint[NewFile](SexpSymbol("new"))

  /**
   * These implicit vals are actually optional - S-Express doesn't
   * *need* them - and exist only to help the compiler to resolve
   * various implicits without recomputing them. Runtime performance
   * is also improved by having these assigned to vals.
   */
  implicit val DebugPrimitiveValueFormat: SexpFormat[DebugPrimitiveValue] = cachedImplicit
  implicit val DebugObjectInstanceFormat: SexpFormat[DebugObjectInstance] = cachedImplicit
  implicit val DebugArrayInstanceFormat: SexpFormat[DebugArrayInstance] = cachedImplicit
  implicit val DebugStringInstanceFormat: SexpFormat[DebugStringInstance] = cachedImplicit
  implicit val DebugNullValueFormat: SexpFormat[DebugNullValue] = cachedImplicit
  implicit val DebugClassFieldFormat: SexpFormat[DebugClassField] = cachedImplicit
  implicit val DebugStackLocalFormat: SexpFormat[DebugStackLocal] = cachedImplicit
  implicit val DebugStackFrameFormat: SexpFormat[DebugStackFrame] = cachedImplicit
  implicit val DebugBacktraceFormat: SexpFormat[DebugBacktrace] = cachedImplicit
  implicit val OffsetSourcePositionFormat: SexpFormat[OffsetSourcePosition] = cachedImplicit
  implicit val LineSourcePositionFormat: SexpFormat[LineSourcePosition] = cachedImplicit
  implicit val ConnectionInfoFormat: SexpFormat[ConnectionInfo] = cachedImplicit
  implicit val SendBackgroundMessageEventFormat: SexpFormat[SendBackgroundMessageEvent] = cachedImplicit
  implicit val BreakpointFormat: SexpFormat[Breakpoint] = cachedImplicit
  implicit val BreakpointListFormat: SexpFormat[BreakpointList] = cachedImplicit
  implicit val FileRangeFormat: SexpFormat[FileRange] = cachedImplicit
  implicit val ERangePositionFormat: SexpFormat[ERangePosition] = cachedImplicit
  implicit val RefactorFailureFormat: SexpFormat[RefactorFailure] = cachedImplicit
  implicit val TextEditFormat: SexpFormat[TextEdit] = cachedImplicit
  implicit val NewFileFormat: SexpFormat[NewFile] = cachedImplicit
  implicit val DeleteFileFormat: SexpFormat[DeleteFile] = cachedImplicit
  implicit val DebugVmErrorFormat: SexpFormat[DebugVmError] = cachedImplicit
  implicit val EmptySourcePositionFormat: SexpFormat[EmptySourcePosition] = cachedImplicit

  implicit object ERangePositionsFormat extends SexpFormat[ERangePositions] {
    def read(s: Sexp): ERangePositions = ???
    def write(rs: ERangePositions): Sexp = rs.positions.toSexp
  }

  implicit object DebugValueFormat extends TraitFormatAlt[DebugValue] {
    override val key = SexpSymbol(":val-type")
    def write(dv: DebugValue): Sexp = dv match {
      case dpv: DebugPrimitiveValue => wrap(dpv)
      case doi: DebugObjectInstance => wrap(doi)
      case dai: DebugArrayInstance => wrap(dai)
      case dsi: DebugStringInstance => wrap(dsi)
      case dnv: DebugNullValue => wrap(dnv)
    }
    def read(hint: SexpSymbol, value: Sexp): DebugValue = hint match {
      case s if s == DebugPrimitiveHint.hint =>
        value.convertTo[DebugPrimitiveValue]
      case s if s == DebugObjectHint.hint =>
        value.convertTo[DebugObjectInstance]
      case s if s == DebugArrayHint.hint =>
        value.convertTo[DebugArrayInstance]
      case s if s == DebugStringHint.hint =>
        value.convertTo[DebugStringInstance]
      case s if s == DebugNullHint.hint =>
        value.convertTo[DebugNullValue]
      case _ => deserializationError(hint)
    }
  }

  implicit object SourcePositionFormat extends TraitFormatAlt[SourcePosition] {
    def write(dl: SourcePosition): Sexp = dl match {
      case empty: EmptySourcePosition => wrap(empty)
      case line: LineSourcePosition => wrap(line)
      case offset: OffsetSourcePosition => wrap(offset)
    }
    def read(hint: SexpSymbol, value: Sexp): SourcePosition = hint match {
      case s if s == implicitly[TypeHint[EmptySourcePosition]].hint =>
        value.convertTo[EmptySourcePosition]
      case s if s == implicitly[TypeHint[LineSourcePosition]].hint =>
        value.convertTo[LineSourcePosition]
      case s if s == implicitly[TypeHint[OffsetSourcePosition]].hint =>
        value.convertTo[OffsetSourcePosition]
      case _ => deserializationError(hint)
    }
  }

  implicit object NoteSeverityFormat extends TraitFormat[NoteSeverity] {
    def write(ns: NoteSeverity): Sexp = ns match {
      case NoteError => NoteErrorHint.hint
      case NoteWarn => NoteWarnHint.hint
      case NoteInfo => NoteInfoHint.hint
    }
    def read(hint: SexpSymbol, value: Sexp): NoteSeverity = hint match {
      case s if s == NoteErrorHint.hint => NoteError
      case s if s == NoteWarnHint.hint => NoteWarn
      case s if s == NoteInfoHint.hint => NoteInfo
      case _ => deserializationError(hint)
    }
  }
  // must be defined after NoteSeverity
  implicit val NoteFormat: SexpFormat[Note] = cachedImplicit
  implicit val NewScalaNotesEventFormat: SexpFormat[NewScalaNotesEvent] = cachedImplicit
  implicit val NewJavaNotesEventFormat: SexpFormat[NewJavaNotesEvent] = cachedImplicit

  implicit object DebugEventFormat extends TraitFormatAlt[DebugEvent] {
    def write(ee: DebugEvent): Sexp = ee match {
      case dse: DebugStepEvent => wrap(dse)
      case dbe: DebugBreakEvent => wrap(dbe)
      case DebugVmStartEvent => wrap(DebugVmStartEvent)
      case DebugVmDisconnectEvent => wrap(DebugVmDisconnectEvent)
      case dee: DebugExceptionEvent => wrap(dee)
      case dts: DebugThreadStartEvent => wrap(dts)
      case dtd: DebugThreadDeathEvent => wrap(dtd)
      case doe: DebugOutputEvent => wrap(doe)
    }
    def read(hint: SexpSymbol, value: Sexp): DebugEvent = hint match {
      case s if s == DebugStepHint.hint => value.convertTo[DebugStepEvent]
      case s if s == DebugBreakHint.hint => value.convertTo[DebugBreakEvent]
      case s if s == DebugVMStartHint.hint => DebugVmStartEvent
      case s if s == DebugVMDisconnectHint.hint => DebugVmDisconnectEvent
      case s if s == DebugExceptionHint.hint => value.convertTo[DebugExceptionEvent]
      case s if s == DebugThreadStartHint.hint => value.convertTo[DebugThreadStartEvent]
      case s if s == DebugThreadDeathHint.hint => value.convertTo[DebugThreadDeathEvent]
      case s if s == DebugOutputHint.hint => value.convertTo[DebugOutputEvent]
      case _ => deserializationError(hint)
    }
  }

  /**
   * This is a tricky one to retrofit:
   *  1. GeneralSwankEvents use the TraitFormat with custom hints
   *  2. DebugEvents use the TraitFormat with another TraitFormatAlt inside
   */
  implicit object EnsimeEventFormat extends TraitFormat[EnsimeEvent] {
    def write(ee: EnsimeEvent): Sexp = ee match {
      case e: AnalyzerReadyEvent.type => wrap(e)
      case e: FullTypeCheckCompleteEvent.type => wrap(e)
      case e: IndexerReadyEvent.type => wrap(e)
      case e: CompilerRestartedEvent.type => wrap(e)
      case nsc: NewScalaNotesEvent => wrap(nsc)
      case e: ClearAllScalaNotesEvent.type => wrap(e)
      case njc: NewJavaNotesEvent => wrap(njc)
      case e: ClearAllJavaNotesEvent.type => wrap(e)
      case sbm: SendBackgroundMessageEvent => SexpList(
        // the odd one out...
        SendBackgroundMessageHint.hint,
        SexpNumber(sbm.code),
        sbm.detail.toSexp
      )
      case de: DebugEvent => wrap(de)
    }
    def read(hint: SexpSymbol, value: Sexp): EnsimeEvent = hint match {
      case s if s == AnalyzerReadyHint.hint => AnalyzerReadyEvent
      case s if s == FullTypeCheckCompleteHint.hint => FullTypeCheckCompleteEvent
      case s if s == IndexerReadyHint.hint => IndexerReadyEvent
      case s if s == CompilerRestartedHint.hint => CompilerRestartedEvent
      case s if s == NewScalaNotesHint.hint => value.convertTo[NewScalaNotesEvent]
      case s if s == ClearAllScalaNotesHint.hint => ClearAllScalaNotesEvent
      case s if s == NewJavaNotesHint.hint => value.convertTo[NewJavaNotesEvent]
      case s if s == ClearAllJavaNotesHint.hint => ClearAllJavaNotesEvent
      case s if s == SendBackgroundMessageHint.hint => ??? // unsupported
      case s if s == DebugHint.hint => value.convertTo[DebugEvent]
      case _ => deserializationError(hint)
    }
  }

  // watch out for recursive references here...
  implicit object TypeInfoFormat extends TraitFormatAlt[TypeInfo] {
    // a bit weird, but that's how we've been doing it
    override val key = SexpSymbol(":arrow-type")
    def write(ti: TypeInfo): Sexp = ti match {
      case arrow: ArrowTypeInfo => wrap(arrow)
      case basic: BasicTypeInfo => wrap(basic)
    }
    def read(hint: SexpSymbol, value: Sexp): TypeInfo = hint match {
      case s if s == ArrowTypeHint.hint => value.convertTo[ArrowTypeInfo]
      case s if s == BasicTypeHint.hint => value.convertTo[BasicTypeInfo]
      case _ => deserializationError(hint)
    }
  }
  implicit object EntityInfoFormat extends TraitFormatAlt[EntityInfo] {
    override val key = SexpSymbol(":info-type")
    def write(ti: EntityInfo): Sexp = ti match {
      case named: NamedTypeMemberInfo => wrap(named)
      case pack: PackageInfo => wrap(pack)
      case tpe: TypeInfo => wrap(tpe)
    }
    def read(hint: SexpSymbol, value: Sexp): EntityInfo = hint match {
      case s if s == NamedTypeMemberHint.hint => value.convertTo[NamedTypeMemberInfo]
      case s if s == PackageHint.hint => value.convertTo[PackageInfo]
      case s if s == TypeInfoHint.hint => value.convertTo[TypeInfo]
      case _ => deserializationError(hint)
    }
  }

  implicit def NamedTypeMemberInfoFormat: SexpFormat[NamedTypeMemberInfo] = {
    def NamedTypeMemberInfoFormat = ???
    implicitly[SexpFormat[NamedTypeMemberInfo]]
  }
  implicit def PackageInfoFormat: SexpFormat[PackageInfo] = { def PackageInfoFormat = ???; implicitly[SexpFormat[PackageInfo]] }
  implicit def ParamSectionInfoFormat: SexpFormat[ParamSectionInfo] = { def ParamSectionInfoFormat = ???; implicitly[SexpFormat[ParamSectionInfo]] }
  implicit def ArrowTypeInfoFormat: SexpFormat[ArrowTypeInfo] = { def ArrowTypeInfoFormat = ???; implicitly[SexpFormat[ArrowTypeInfo]] }
  implicit def BasicTypeInfoFormat: SexpFormat[BasicTypeInfo] = { def BasicTypeInfoFormat = ???; implicitly[SexpFormat[BasicTypeInfo]] }
  implicit def SymbolInfoFormat: SexpFormat[SymbolInfo] = { def SymbolInfoFormat = ???; implicitly[SexpFormat[SymbolInfo]] }
  implicit def InterfaceInfoFormat: SexpFormat[InterfaceInfo] = { def InterfaceInfoFormat = ???; implicitly[SexpFormat[InterfaceInfo]] }
  implicit def TypeInspectInfoFormat: SexpFormat[TypeInspectInfo] = { def TypeInspectInfoFormat = ???; implicitly[SexpFormat[TypeInspectInfo]] }

  // must be defined after CompletionSignatureFormat and TypeInfo
  implicit val CompletionInfoFormat: SexpFormat[CompletionInfo] = cachedImplicit
  implicit val CompletionInfoListFormat: SexpFormat[CompletionInfoList] = cachedImplicit

  implicit object FileEditFormat extends TraitFormatAlt[FileEdit] {
    def write(ti: FileEdit): Sexp = ti match {
      case text: TextEdit => wrap(text)
      case nf: NewFile => wrap(nf)
      case df: DeleteFile => wrap(df)
    }
    def read(hint: SexpSymbol, value: Sexp): FileEdit = hint match {
      case t if t == implicitly[TypeHint[TextEdit]].hint => value.convertTo[TextEdit]
      case t if t == implicitly[TypeHint[NewFile]].hint => value.convertTo[NewFile]
      case t if t == implicitly[TypeHint[DeleteFile]].hint => value.convertTo[DeleteFile]
      case _ => deserializationError(hint)
    }
  }
  // must be after FileEditFormat
  implicit val RefactorDiffEffectFormat: SexpFormat[RefactorDiffEffect] = cachedImplicit

  // must be after SourcePosition
  implicit val TypeSearchResultFormat: SexpFormat[TypeSearchResult] = cachedImplicit
  implicit val MethodSearchResultFormat: SexpFormat[MethodSearchResult] = cachedImplicit
  implicit object SymbolSearchResultFormat extends TraitFormatAlt[SymbolSearchResult] {
    def write(ti: SymbolSearchResult): Sexp = ti match {
      case ts: TypeSearchResult => wrap(ts)
      case ms: MethodSearchResult => wrap(ms)
    }
    def read(hint: SexpSymbol, value: Sexp): SymbolSearchResult = hint match {
      case t if t == implicitly[TypeHint[TypeSearchResult]].hint => value.convertTo[TypeSearchResult]
      case t if t == implicitly[TypeHint[MethodSearchResult]].hint => value.convertTo[MethodSearchResult]
      case _ => deserializationError(hint)
    }
  }

  implicit object SymbolSearchResultsFormat extends SexpFormat[SymbolSearchResults] {
    def write(o: SymbolSearchResults): Sexp = o.syms.toSexp
    def read(sexp: Sexp): SymbolSearchResults = SymbolSearchResults(
      sexp.convertTo[List[SymbolSearchResult]]
    )
  }
  implicit object ImportSuggestionsFormat extends SexpFormat[ImportSuggestions] {
    def write(o: ImportSuggestions): Sexp = o.symLists.toSexp
    def read(sexp: Sexp): ImportSuggestions = ImportSuggestions(
      sexp.convertTo[List[List[SymbolSearchResult]]]
    )
  }

  // must be after SourceSymbol
  implicit object SymbolDesignationFormat extends SexpFormat[SymbolDesignation] {
    def write(o: SymbolDesignation): Sexp =
      SexpList(
        SexpSymbol(sourceSymbolToSymbol(o.symType)),
        o.start.toSexp,
        o.end.toSexp
      )
    def read(sexp: Sexp): SymbolDesignation = ???
  }
  implicit val SymbolDesignationsFormat: SexpFormat[SymbolDesignations] = cachedImplicit

  implicit val ImplicitConversionInfoHint: TypeHint[ImplicitConversionInfo] = TypeHint[ImplicitConversionInfo](SexpSymbol("conversion"))
  implicit val ImplicitParamInfoHint: TypeHint[ImplicitParamInfo] = TypeHint[ImplicitParamInfo](SexpSymbol("param"))
  implicit object ImplicitInfoFormat extends TraitFormatAlt[ImplicitInfo] {
    def write(i: ImplicitInfo): Sexp = i match {
      case c: ImplicitConversionInfo => wrap(c)
      case p: ImplicitParamInfo => wrap(p)
    }
    def read(hint: SexpSymbol, sexp: Sexp): ImplicitInfo = ???
  }
  implicit object ImplcitInfosFormat extends SexpFormat[ImplicitInfos] {
    def write(o: ImplicitInfos): Sexp = o.infos.toSexp
    def read(sexp: Sexp) = ???
  }

  implicit object DebugVmStatusFormat extends TraitFormatAlt[DebugVmStatus] {
    def write(ti: DebugVmStatus): Sexp = ti match {
      case s: DebugVmSuccess => wrap(s)
      case e: DebugVmError => wrap(e)
    }
    def read(hint: SexpSymbol, value: Sexp): DebugVmStatus = hint match {
      case t if t == DebugVmSuccessHint.hint => value.convertTo[DebugVmSuccess]
      case t if t == DebugVmErrorHint.hint => value.convertTo[DebugVmError]
      case _ => deserializationError(hint)
    }
  }
  implicit def StructureViewFormat: SexpFormat[StructureView] = { def StructureViewFormat = ???; implicitly[SexpFormat[StructureView]] }

  implicit def AstInfoFormat: SexpFormat[AstInfo] = { def AstInfoFormat = ???; implicitly[SexpFormat[AstInfo]] }

  implicit object RpcResponseFormat extends SexpFormat[RpcResponse] {
    def read(sexp: Sexp): RpcResponse = ???
    def write(r: RpcResponse): Sexp = r match {
      case VoidResponse => false.toSexp
      case TrueResponse => true.toSexp
      case FalseResponse => false.toSexp
      case value: StringResponse => value.text.toSexp

      case value: ConnectionInfo => value.toSexp

      case value: NamedTypeMemberInfo => value.toSexp
      case value: TypeInfo => value.toSexp
      case value: EntityInfo => value.toSexp
      case value: SymbolSearchResult => value.toSexp
      case value: DebugVmStatus => value.toSexp

      case value: SourcePosition => value.toSexp
      case value: DebugLocation => value.toSexp
      case value: DebugValue => value.toSexp
      case value: DebugClassField => value.toSexp
      case value: DebugStackLocal => value.toSexp
      case value: DebugStackFrame => value.toSexp
      case value: DebugBacktrace => value.toSexp
      case value: Breakpoint => value.toSexp
      case value: BreakpointList => value.toSexp
      case value: Note => value.toSexp
      case value: CompletionInfo => value.toSexp
      case value: CompletionInfoList => value.toSexp
      case value: SymbolInfo => value.toSexp
      case value: InterfaceInfo => value.toSexp
      case value: TypeInspectInfo => value.toSexp
      case value: SymbolSearchResults => value.toSexp
      case value: ImportSuggestions => value.toSexp
      case value: ERangePositions => value.toSexp
      case value: FileRange => value.toSexp
      case value: SymbolDesignations => value.toSexp
      case value: RefactorFailure => value.toSexp
      case value: RefactorDiffEffect => value.toSexp
      case value: ImplicitInfos => value.toSexp
      case value: StructureView => value.toSexp
      case value: AstInfo => value.toSexp
      case error: EnsimeServerError =>
        throw new IllegalArgumentException(
          s"for legacy reasons, RpcError should be marshalled as an EnsimeServerMessage: $error"
        )
    }
  }

  implicit object EnsimeServerMessageFormat extends SexpFormat[EnsimeServerMessage] {
    def read(sexp: Sexp): EnsimeServerMessage = ???
    def write(o: EnsimeServerMessage): Sexp = o match {
      case r: RpcResponse => r.toSexp
      case e: EnsimeEvent => e.toSexp
    }
  }

  object RpcResponseEnvelopeFormat extends SexpFormat[RpcResponseEnvelope] {
    def read(sexp: Sexp): RpcResponseEnvelope = ???
    def write(o: RpcResponseEnvelope): Sexp = o match {
      case RpcResponseEnvelope(_, event: EnsimeEvent) => event.toSexp
      case RpcResponseEnvelope(Some(callId), EnsimeServerError(detail)) => SexpList(
        SexpSymbol(":return"),
        SexpList(SexpSymbol(":abort"), SexpNumber(666), SexpString(detail)),
        SexpNumber(callId)
      )
      case RpcResponseEnvelope(Some(callId), payload) => SexpList(
        SexpSymbol(":return"),
        SexpList(SexpSymbol(":ok"), payload.toSexp),
        SexpNumber(callId)
      )
    }
  }

}

// we get diverging implicits if everything is in the one object
object SwankProtocolRequest {
  import SwankProtocolConversions._
  import SwankProtocolCommon._

  // I don't know why, but OffsetRangeFormat needs to be here
  implicit object OffsetRangeFormat extends SexpFormat[OffsetRange] {
    def write(or: OffsetRange): Sexp = ???
    def read(sexp: Sexp): OffsetRange = sexp match {
      case SexpNumber(a) =>
        OffsetRange(a.intValue, a.intValue)
      case SexpList(SexpNumber(a) :: SexpNumber(b) :: Nil) =>
        OffsetRange(a.intValue, b.intValue)
      case _ => deserializationError(sexp)
    }
  }

  implicit val ConnectionInfoReqHint: TypeHint[ConnectionInfoReq.type] = TypeHint[ConnectionInfoReq.type](SexpSymbol("swank:connection-info"))

  implicit val RemoveFileReqHint: TypeHint[RemoveFileReq] = TypeHint[RemoveFileReq](SexpSymbol("swank:remove-file"))
  implicit val UnloadFileReqHint: TypeHint[UnloadFileReq] = TypeHint[UnloadFileReq](SexpSymbol("swank:unload-file"))
  implicit val TypecheckFileReqHint: TypeHint[TypecheckFileReq] = TypeHint[TypecheckFileReq](SexpSymbol("swank:typecheck-file"))
  implicit val TypecheckFilesReqHint: TypeHint[TypecheckFilesReq] = TypeHint[TypecheckFilesReq](SexpSymbol("swank:typecheck-files"))
  implicit val UnloadAllReqHint: TypeHint[UnloadAllReq.type] = TypeHint[UnloadAllReq.type](SexpSymbol("swank:unload-all"))
  implicit val TypecheckAllReqHint: TypeHint[TypecheckAllReq.type] = TypeHint[TypecheckAllReq.type](SexpSymbol("swank:typecheck-all"))
  implicit val PublicSymbolSearchReqHint: TypeHint[PublicSymbolSearchReq] = TypeHint[PublicSymbolSearchReq](SexpSymbol("swank:public-symbol-search"))
  implicit val ImportSuggestionsReqHint: TypeHint[ImportSuggestionsReq] = TypeHint[ImportSuggestionsReq](SexpSymbol("swank:import-suggestions"))
  implicit val DocUriAtPointReqHint: TypeHint[DocUriAtPointReq] = TypeHint[DocUriAtPointReq](SexpSymbol("swank:doc-uri-at-point"))
  implicit val DocUriForSymbolReqHint: TypeHint[DocUriForSymbolReq] = TypeHint[DocUriForSymbolReq](SexpSymbol("swank:doc-uri-for-symbol"))
  implicit val CompletionsReqHint: TypeHint[CompletionsReq] = TypeHint[CompletionsReq](SexpSymbol("swank:completions"))
  implicit val PackageMemberCompletionReqHint: TypeHint[PackageMemberCompletionReq] = TypeHint[PackageMemberCompletionReq](SexpSymbol("swank:package-member-completion"))
  implicit val UsesOfSymbolAtPointReqHint: TypeHint[UsesOfSymbolAtPointReq] = TypeHint[UsesOfSymbolAtPointReq](SexpSymbol("swank:uses-of-symbol-at-point"))
  implicit val TypeByNameReqHint: TypeHint[TypeByNameReq] = TypeHint[TypeByNameReq](SexpSymbol("swank:type-by-name"))
  implicit val TypeByNameAtPointReqHint: TypeHint[TypeByNameAtPointReq] = TypeHint[TypeByNameAtPointReq](SexpSymbol("swank:type-by-name-at-point"))
  implicit val TypeAtPointReqHint: TypeHint[TypeAtPointReq] = TypeHint[TypeAtPointReq](SexpSymbol("swank:type-at-point"))
  implicit val InspectTypeAtPointReqHint: TypeHint[InspectTypeAtPointReq] = TypeHint[InspectTypeAtPointReq](SexpSymbol("swank:inspect-type-at-point"))
  implicit val InspectTypeByNameReqHint: TypeHint[InspectTypeByNameReq] = TypeHint[InspectTypeByNameReq](SexpSymbol("swank:inspect-type-by-name"))
  implicit val SymbolAtPointReqHint: TypeHint[SymbolAtPointReq] = TypeHint[SymbolAtPointReq](SexpSymbol("swank:symbol-at-point"))
  implicit val SymbolByNameReqHint: TypeHint[SymbolByNameReq] = TypeHint[SymbolByNameReq](SexpSymbol("swank:symbol-by-name"))
  implicit val InspectPackageByPathReqHint: TypeHint[InspectPackageByPathReq] = TypeHint[InspectPackageByPathReq](SexpSymbol("swank:inspect-package-by-path"))
  implicit val RefactorReqHint: TypeHint[RefactorReq] = TypeHint[RefactorReq](SexpSymbol("swank:diff-refactor"))
  implicit val SymbolDesignationsReqHint: TypeHint[SymbolDesignationsReq] = TypeHint[SymbolDesignationsReq](SexpSymbol("swank:symbol-designations"))
  implicit val ImplicitInfoReqHint: TypeHint[ImplicitInfoReq] = TypeHint[ImplicitInfoReq](SexpSymbol("swank:implicit-info"))
  implicit val ExpandSelectionReqHint: TypeHint[ExpandSelectionReq] = TypeHint[ExpandSelectionReq](SexpSymbol("swank:expand-selection"))
  implicit val StructureViewReqHint: TypeHint[StructureViewReq] = TypeHint[StructureViewReq](SexpSymbol("swank:structure-view"))
  implicit val AstAtPointReqHint: TypeHint[AstAtPointReq] = TypeHint[AstAtPointReq](SexpSymbol("swank:ast-at-point"))
  implicit val DebugActiveVmReqHint: TypeHint[DebugActiveVmReq.type] = TypeHint[DebugActiveVmReq.type](SexpSymbol("swank:debug-active-vm"))
  implicit val DebugAttachReqHint: TypeHint[DebugAttachReq] = TypeHint[DebugAttachReq](SexpSymbol("swank:debug-attach"))
  implicit val DebugStopReqHint: TypeHint[DebugStopReq.type] = TypeHint[DebugStopReq.type](SexpSymbol("swank:debug-stop"))
  implicit val DebugSetBreakReqHint: TypeHint[DebugSetBreakReq] = TypeHint[DebugSetBreakReq](SexpSymbol("swank:debug-set-break"))
  implicit val DebugClearBreakReqHint: TypeHint[DebugClearBreakReq] = TypeHint[DebugClearBreakReq](SexpSymbol("swank:debug-clear-break"))
  implicit val DebugClearAllBreaksReqHint: TypeHint[DebugClearAllBreaksReq.type] = TypeHint[DebugClearAllBreaksReq.type](SexpSymbol("swank:debug-clear-all-breaks"))
  implicit val DebugListBreakpointsReqHint: TypeHint[DebugListBreakpointsReq.type] = TypeHint[DebugListBreakpointsReq.type](SexpSymbol("swank:debug-list-breakpoints"))
  implicit val DebugRunReqHint: TypeHint[DebugRunReq.type] = TypeHint[DebugRunReq.type](SexpSymbol("swank:debug-run"))
  implicit val DebugContinueReqHint: TypeHint[DebugContinueReq] = TypeHint[DebugContinueReq](SexpSymbol("swank:debug-continue"))
  implicit val DebugStepReqHint: TypeHint[DebugStepReq] = TypeHint[DebugStepReq](SexpSymbol("swank:debug-step"))
  implicit val DebugNextReqHint: TypeHint[DebugNextReq] = TypeHint[DebugNextReq](SexpSymbol("swank:debug-next"))
  implicit val DebugStepOutReqHint: TypeHint[DebugStepOutReq] = TypeHint[DebugStepOutReq](SexpSymbol("swank:debug-step-out"))
  implicit val DebugLocateNameReqHint: TypeHint[DebugLocateNameReq] = TypeHint[DebugLocateNameReq](SexpSymbol("swank:debug-locate-name"))
  implicit val DebugValueReqHint: TypeHint[DebugValueReq] = TypeHint[DebugValueReq](SexpSymbol("swank:debug-value"))
  implicit val DebugToStringReqHint: TypeHint[DebugToStringReq] = TypeHint[DebugToStringReq](SexpSymbol("swank:debug-to-string"))
  implicit val DebugSetValueReqHint: TypeHint[DebugSetValueReq] = TypeHint[DebugSetValueReq](SexpSymbol("swank:debug-set-value"))
  implicit val DebugBacktraceReqHint: TypeHint[DebugBacktraceReq] = TypeHint[DebugBacktraceReq](SexpSymbol("swank:debug-backtrace"))

  // higher priority than labelledProductFormat, so SexpFormat[T]
  // should pick up on this instead, also private so we don't
  // accidentally export it.
  private implicit def tupledProductFormat[T <: RpcRequest, R <: shapeless.HList](
    implicit
    g: shapeless.Generic.Aux[T, R],
    r: HListFormat[R]
  ): SexpFormat[T] = new SexpFormat[T] {
    def write(x: T): Sexp = SexpList(r.write(g.to(x)))

    def read(value: Sexp): T = value match {
      case SexpNil => g.from(r.read(Nil))
      case SexpList(els) =>
        g.from(r.read(els))
      case x =>
        deserializationError(x)
    }
  }

  implicit object PatchOpFormat extends SexpFormat[PatchOp] {
    def write(v: PatchOp): Sexp = ???
    def read(sexp: Sexp): PatchOp = sexp match {
      case SexpList(SexpString("+") :: SexpNumber(i) :: SexpString(text) :: Nil) =>
        PatchInsert(i.intValue, text)
      case SexpList(SexpString("*") :: SexpNumber(i) :: SexpNumber(j) :: SexpString(text) :: Nil) =>
        PatchReplace(i.intValue, j.intValue, text)
      case SexpList(SexpString("-") :: SexpNumber(i) :: SexpNumber(j) :: Nil) =>
        PatchDelete(i.intValue, j.intValue)
      case _ => deserializationError(sexp)
    }
  }

  // this works only because the parameter lists are mutually
  // exclusive it might not agree with the `tpe` given on
  // PrepareRefactorReq
  implicit object RefactorDescFormat extends SexpFormat[RefactorDesc] {
    import org.ensime.api.{ RefactorLocation => Loc }
    import org.ensime.util.file._

    def write(v: RefactorDesc): Sexp = ???
    def read(sexp: Sexp): RefactorDesc = sexp match {
      case SexpList(params) =>
        params.grouped(2).collect {
          case List(SexpSymbol("qualifiedName"), value) => (Loc.QualifiedName, value)
          case List(SexpSymbol("file"), value) => (Loc.File, value)
          case List(SexpSymbol("newName"), value) => (Loc.NewName, value)
          case List(SexpSymbol("name"), value) => (Loc.Name, value)
          case List(SexpSymbol("start"), value) => (Loc.Start, value)
          case List(SexpSymbol("end"), value) => (Loc.End, value)
          case List(SexpSymbol("methodName"), value) => (Loc.MethodName, value)
        }.toList.sortBy(_._1.symbol.name) match {
          case List(
            (Loc.End, SexpNumber(end)),
            (Loc.File, SexpString(f)),
            (Loc.NewName, SexpString(newName)),
            (Loc.Start, SexpNumber(start))
            ) => RenameRefactorDesc(newName, File(f).canon, start.intValue, end.intValue)

          case List(
            (Loc.End, SexpNumber(end)),
            (Loc.File, SexpString(f)),
            (Loc.MethodName, SexpString(methodName)),
            (Loc.Start, SexpNumber(start))
            ) => ExtractMethodRefactorDesc(methodName, File(f).canon, start.intValue, end.intValue)

          case List(
            (Loc.End, SexpNumber(end)),
            (Loc.File, SexpString(f)),
            (Loc.Name, SexpString(name)),
            (Loc.Start, SexpNumber(start))
            ) => ExtractLocalRefactorDesc(name, File(f).canon, start.intValue, end.intValue)

          case List(
            (Loc.End, SexpNumber(end)),
            (Loc.File, SexpString(f)),
            (Loc.Start, SexpNumber(start))
            ) => InlineLocalRefactorDesc(File(f).canon, start.intValue, end.intValue)

          case List(
            (Loc.File, SexpString(f))
            ) =>
            OrganiseImportsRefactorDesc(File(f).canon)

          case List(
            (Loc.End, SexpNumber(_)),
            (Loc.File, SexpString(f)),
            (Loc.QualifiedName, SexpString(qualifiedName)),
            (Loc.Start, SexpNumber(_))
            ) => AddImportRefactorDesc(qualifiedName, File(f).canon)

          case List(
            (Loc.File, SexpString(f)),
            (Loc.QualifiedName, SexpString(qualifiedName))
            ) => AddImportRefactorDesc(qualifiedName, File(f).canon)

          case _ => deserializationError(sexp)
        }
      case _ => deserializationError(sexp)
    }
  }

  // incoming messages
  implicit def RemoveFileReqFormat: SexpFormat[RemoveFileReq] = { def RemoveFileReqFormat = ???; implicitly[SexpFormat[RemoveFileReq]] }
  implicit def UnloadFileReqFormat: SexpFormat[UnloadFileReq] = { def UnloadFileReqFormat = ???; implicitly[SexpFormat[UnloadFileReq]] }
  implicit def TypecheckFileReqFormat: SexpFormat[TypecheckFileReq] = { def TypecheckFileReqFormat = ???; implicitly[SexpFormat[TypecheckFileReq]] }
  implicit def TypecheckFilesReqFormat: SexpFormat[TypecheckFilesReq] = { def TypecheckFilesReqFormat = ???; implicitly[SexpFormat[TypecheckFilesReq]] }
  implicit def PublicSymbolSearchHint: SexpFormat[PublicSymbolSearchReq] = { def PublicSymbolSearchHint = ???; implicitly[SexpFormat[PublicSymbolSearchReq]] }
  implicit def ImportSuggestionsReqFormat: SexpFormat[ImportSuggestionsReq] = { def ImportSuggestionsReqFormat = ???; implicitly[SexpFormat[ImportSuggestionsReq]] }
  implicit def DocUriAtPointReqFormat: SexpFormat[DocUriAtPointReq] = { def DocUriAtPointReqFormat = ???; implicitly[SexpFormat[DocUriAtPointReq]] }
  implicit def DocUriForSymbolReqFormat: SexpFormat[DocUriForSymbolReq] = { def DocUriForSymbolReqFormat = ???; implicitly[SexpFormat[DocUriForSymbolReq]] }
  implicit def CompletionsReqFormat: SexpFormat[CompletionsReq] = { def CompletionsReqFormat = ???; implicitly[SexpFormat[CompletionsReq]] }
  implicit def PackageMemberCompletionReqFormat: SexpFormat[PackageMemberCompletionReq] = { def PackageMemberCompletionReqFormat = ???; implicitly[SexpFormat[PackageMemberCompletionReq]] }
  implicit def UsesOfSymbolAtPointReqFormat: SexpFormat[UsesOfSymbolAtPointReq] = { def UsesOfSymbolAtPointReqFormat = ???; implicitly[SexpFormat[UsesOfSymbolAtPointReq]] }
  implicit def TypeByNameReqFormat: SexpFormat[TypeByNameReq] = { def TypeByNameReqFormat = ???; implicitly[SexpFormat[TypeByNameReq]] }
  implicit def TypeByNameAtPointReqFormat: SexpFormat[TypeByNameAtPointReq] = { def TypeByNameAtPointReqFormat = ???; implicitly[SexpFormat[TypeByNameAtPointReq]] }
  implicit def TypeAtPointReqFormat: SexpFormat[TypeAtPointReq] = { def TypeAtPointReqFormat = ???; implicitly[SexpFormat[TypeAtPointReq]] }
  implicit def InspectTypeAtPointReqFormat: SexpFormat[InspectTypeAtPointReq] = { def InspectTypeAtPointReqFormat = ???; implicitly[SexpFormat[InspectTypeAtPointReq]] }
  implicit def InspectTypeByNameReqFormat: SexpFormat[InspectTypeByNameReq] = { def InspectTypeByNameReqFormat = ???; implicitly[SexpFormat[InspectTypeByNameReq]] }
  implicit def SymbolAtPointReqFormat: SexpFormat[SymbolAtPointReq] = { def SymbolAtPointReqFormat = ???; implicitly[SexpFormat[SymbolAtPointReq]] }
  implicit def SymbolByNameReqFormat: SexpFormat[SymbolByNameReq] = { def SymbolByNameReqFormat = ???; implicitly[SexpFormat[SymbolByNameReq]] }
  implicit def InspectPackageByPathReqFormat: SexpFormat[InspectPackageByPathReq] = { def InspectPackageByPathReqFormat = ???; implicitly[SexpFormat[InspectPackageByPathReq]] }
  implicit def RefactorReqFormat: SexpFormat[RefactorReq] = { def RefactorReqFormat = ???; implicitly[SexpFormat[RefactorReq]] }
  implicit def SymbolDesignationsReqFormat: SexpFormat[SymbolDesignationsReq] = { def SymbolDesignationsReqFormat = ???; implicitly[SexpFormat[SymbolDesignationsReq]] }
  implicit def ImplicitInfoReqFormat: SexpFormat[ImplicitInfoReq] = { def ImplicitInfoReqFormat = ???; implicitly[SexpFormat[ImplicitInfoReq]] }
  implicit def ExpandSelectionReqFormat: SexpFormat[ExpandSelectionReq] = { def ExpandSelectionReqFormat = ???; implicitly[SexpFormat[ExpandSelectionReq]] }
  implicit def StructureViewReqFormat: SexpFormat[StructureViewReq] = { def StructureViewReqFormat = ???; implicitly[SexpFormat[StructureViewReq]] }
  implicit def AstAtPointReqFormat: SexpFormat[AstAtPointReq] = { def AstAtPointReqFormat = ???; implicitly[SexpFormat[AstAtPointReq]] }
  implicit def DebugAttachReqFormat: SexpFormat[DebugAttachReq] = { def DebugAttachReqFormat = ???; implicitly[SexpFormat[DebugAttachReq]] }
  implicit def DebugSetBreakReqFormat: SexpFormat[DebugSetBreakReq] = { def DebugSetBreakReqFormat = ???; implicitly[SexpFormat[DebugSetBreakReq]] }
  implicit def DebugClearBreakReqFormat: SexpFormat[DebugClearBreakReq] = { def DebugClearBreakReqFormat = ???; implicitly[SexpFormat[DebugClearBreakReq]] }
  implicit def DebugContinueReqFormat: SexpFormat[DebugContinueReq] = { def DebugContinueReqFormat = ???; implicitly[SexpFormat[DebugContinueReq]] }
  implicit def DebugStepReqFormat: SexpFormat[DebugStepReq] = { def DebugStepReqFormat = ???; implicitly[SexpFormat[DebugStepReq]] }
  implicit def DebugNextReqFormat: SexpFormat[DebugNextReq] = { def DebugNextReqFormat = ???; implicitly[SexpFormat[DebugNextReq]] }
  implicit def DebugStepOutReqFormat: SexpFormat[DebugStepOutReq] = { def DebugStepOutReqFormat = ???; implicitly[SexpFormat[DebugStepOutReq]] }
  implicit def DebugLocateNameReqFormat: SexpFormat[DebugLocateNameReq] = { def DebugLocateNameReqFormat = ???; implicitly[SexpFormat[DebugLocateNameReq]] }
  implicit def DebugValueReqFormat: SexpFormat[DebugValueReq] = { def DebugValueReqFormat = ???; implicitly[SexpFormat[DebugValueReq]] }
  implicit def DebugToStringReqFormat: SexpFormat[DebugToStringReq] = { def DebugToStringReqFormat = ???; implicitly[SexpFormat[DebugToStringReq]] }
  implicit def DebugSetValueueReqFormat: SexpFormat[DebugSetValueReq] = { def DebugSetValueueReqFormat = ???; implicitly[SexpFormat[DebugSetValueReq]] }
  implicit def DebugBacktraceReqFormat: SexpFormat[DebugBacktraceReq] = { def DebugBacktraceReqFormat = ???; implicitly[SexpFormat[DebugBacktraceReq]] }

  implicit object RpcRequestReader extends SexpReader[RpcRequest] {
    override def read(sexp: Sexp): RpcRequest = sexp match {
      case SexpList((kind: SexpSymbol) :: rest) =>
        val value = SexpList(rest)
        kind match {
          case s if s == ConnectionInfoReqHint.hint => ConnectionInfoReq
          case s if s == RemoveFileReqHint.hint => value.convertTo[RemoveFileReq]
          case s if s == UnloadFileReqHint.hint => value.convertTo[UnloadFileReq]
          case s if s == TypecheckFileReqHint.hint => value.convertTo[TypecheckFileReq]
          case s if s == TypecheckFilesReqHint.hint => value.convertTo[TypecheckFilesReq]
          case s if s == UnloadAllReqHint.hint => UnloadAllReq
          case s if s == TypecheckAllReqHint.hint => TypecheckAllReq
          case s if s == PublicSymbolSearchReqHint.hint => value.convertTo[PublicSymbolSearchReq]
          case s if s == ImportSuggestionsReqHint.hint => value.convertTo[ImportSuggestionsReq]
          case s if s == DocUriAtPointReqHint.hint => value.convertTo[DocUriAtPointReq]
          case s if s == DocUriForSymbolReqHint.hint => value.convertTo[DocUriForSymbolReq]
          case s if s == CompletionsReqHint.hint => value.convertTo[CompletionsReq]
          case s if s == PackageMemberCompletionReqHint.hint => value.convertTo[PackageMemberCompletionReq]
          case s if s == UsesOfSymbolAtPointReqHint.hint => value.convertTo[UsesOfSymbolAtPointReq]
          case s if s == TypeByNameReqHint.hint => value.convertTo[TypeByNameReq]
          case s if s == TypeByNameAtPointReqHint.hint => value.convertTo[TypeByNameAtPointReq]
          case s if s == TypeAtPointReqHint.hint => value.convertTo[TypeAtPointReq]
          case s if s == InspectTypeAtPointReqHint.hint => value.convertTo[InspectTypeAtPointReq]
          case s if s == InspectTypeByNameReqHint.hint => value.convertTo[InspectTypeByNameReq]
          case s if s == SymbolAtPointReqHint.hint => value.convertTo[SymbolAtPointReq]
          case s if s == SymbolByNameReqHint.hint => value.convertTo[SymbolByNameReq]
          case s if s == InspectPackageByPathReqHint.hint => value.convertTo[InspectPackageByPathReq]
          case s if s == RefactorReqHint.hint => value.convertTo[RefactorReq]
          case s if s == SymbolDesignationsReqHint.hint => value.convertTo[SymbolDesignationsReq]
          case s if s == ImplicitInfoReqHint.hint => value.convertTo[ImplicitInfoReq]
          case s if s == ExpandSelectionReqHint.hint => value.convertTo[ExpandSelectionReq]
          case s if s == StructureViewReqHint.hint => value.convertTo[StructureViewReq]
          case s if s == AstAtPointReqHint.hint => value.convertTo[AstAtPointReq]
          case s if s == DebugActiveVmReqHint.hint => DebugActiveVmReq
          case s if s == DebugAttachReqHint.hint => value.convertTo[DebugAttachReq]
          case s if s == DebugStopReqHint.hint => DebugStopReq
          case s if s == DebugSetBreakReqHint.hint => value.convertTo[DebugSetBreakReq]
          case s if s == DebugClearBreakReqHint.hint => value.convertTo[DebugClearBreakReq]
          case s if s == DebugClearAllBreaksReqHint.hint => DebugClearAllBreaksReq
          case s if s == DebugListBreakpointsReqHint.hint => DebugListBreakpointsReq
          case s if s == DebugRunReqHint.hint => DebugRunReq
          case s if s == DebugContinueReqHint.hint => value.convertTo[DebugContinueReq]
          case s if s == DebugStepReqHint.hint => value.convertTo[DebugStepReq]
          case s if s == DebugNextReqHint.hint => value.convertTo[DebugNextReq]
          case s if s == DebugStepOutReqHint.hint => value.convertTo[DebugStepOutReq]
          case s if s == DebugLocateNameReqHint.hint => value.convertTo[DebugLocateNameReq]
          case s if s == DebugValueReqHint.hint => value.convertTo[DebugValueReq]
          case s if s == DebugToStringReqHint.hint => value.convertTo[DebugToStringReq]
          case s if s == DebugSetValueReqHint.hint => value.convertTo[DebugSetValueReq]
          case s if s == DebugBacktraceReqHint.hint => value.convertTo[DebugBacktraceReq]

          case _ => deserializationError(sexp)
        }

      case _ => deserializationError(sexp)
    }
  }

  object RpcRequestEnvelopeFormat extends SexpFormat[RpcRequestEnvelope] {
    def write(env: RpcRequestEnvelope): Sexp = ???
    def read(sexp: Sexp): RpcRequestEnvelope = sexp match {
      case SexpList(SexpSymbol(":swank-rpc") :: form :: SexpNumber(callIdBI) :: Nil) =>
        val callId = callIdBI.intValue()
        Try(form.convertTo[RpcRequest]) match {
          case Success(v) =>
            RpcRequestEnvelope(v, callId)
          case Failure(ex) =>
            // we failed to parse to a valid s, but we have a call id - so we
            // should return an rpc abort rather than :reader-error as emacs tends to bork.
            throw new SwankRPCFormatException(s"Invalid rpc request ${form.compactPrint}", callId, ex)
        }

      case _ => deserializationError(sexp)
    }
  }

}

object SwankFormats {
  implicit val RpcRequestEnvelopeFormat: SexpFormat[RpcRequestEnvelope] = SwankProtocolRequest.RpcRequestEnvelopeFormat
  implicit val RpcResponseEnvelopeFormat: SexpFormat[RpcResponseEnvelope] = SwankProtocolResponse.RpcResponseEnvelopeFormat
}
