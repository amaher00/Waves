package com.wavesplatform.transaction.smart.script

import cats.implicits._
import com.wavesplatform.lang.StdLibVersion
import com.wavesplatform.lang.StdLibVersion.{V1, _}
import com.wavesplatform.lang.directives.{Directive, DirectiveKey, DirectiveParser}
import com.wavesplatform.lang.v1.ScriptEstimator
import com.wavesplatform.lang.v1.compiler.Terms.EXPR
import com.wavesplatform.lang.v1.compiler.{ContractCompiler, ExpressionCompilerV1}
import com.wavesplatform.lang.v1.parser.Parser
import com.wavesplatform.transaction.smart.script.ContractScript._
import com.wavesplatform.transaction.smart.script.v1.ExprScript
import com.wavesplatform.transaction.smart.script.v1.ExprScript.ExprScriprImpl
import com.wavesplatform.utils._

import scala.util.{Failure, Success, Try}

object ScriptCompiler extends ScorexLogging {

  def contract(scriptText: String): Either[String, Script] = {
    val ctx = compilerContext(V3, isAssetScript = false)
    ContractCompiler(ctx, Parser.parseContract(scriptText).get.value)
      .flatMap(s => ContractScript(V3, s))
  }

  def apply(scriptText: String, isAssetScript: Boolean): Either[String, (Script, Long)] = {
    val directives = DirectiveParser(scriptText)

    val scriptWithoutDirectives =
      scriptText.linesIterator
        .filter(str => !str.contains("{-#"))
        .mkString("\n")

    for {
      ver        <- extractVersion(directives)
      expr       <- tryCompile(scriptWithoutDirectives, ver, isAssetScript, directives)
      script     <- ExprScript.apply(ver, expr)
      complexity <- ScriptEstimator(varNames(ver), functionCosts(ver), expr)
    } yield (script, complexity)
  }

  def tryCompile(src: String, version: StdLibVersion, isAssetScript: Boolean, directives: List[Directive]): Either[String, EXPR] = {
    val compiler = new ExpressionCompilerV1(compilerContext(version, isAssetScript))
    try {
      compiler.compile(src, directives)
    } catch {
      case ex: Throwable =>
        log.error("Error compiling script", ex)
        log.error(src)
        val msg = Option(ex.getMessage).getOrElse("Parsing failed: Unknown error")
        Left(msg)
    }
  }

  def estimate(script: Script, version: StdLibVersion): Either[String, Long] = script match {
    case s: ExprScriprImpl => ScriptEstimator(varNames(version), functionCosts(version), s.expr)
    case s: ContractScriptImpl => ContractScript.estimateComplexity(version, s.expr).map(_._2)
    case _                 => ???
  }

  private def extractVersion(directives: List[Directive]): Either[String, StdLibVersion] = {
    directives
      .find(_.key == DirectiveKey.LANGUAGE_VERSION)
      .map(d =>
        Try(d.value.toInt) match {
          case Success(v) =>
            val ver = StdLibVersion(v)
            Either
              .cond(
                SupportedVersions(ver),
                ver,
                "Unsupported language version"
              )
          case Failure(ex) =>
            Left("Can't parse language version")
      })
      .getOrElse(V1.asRight)
  }

}
