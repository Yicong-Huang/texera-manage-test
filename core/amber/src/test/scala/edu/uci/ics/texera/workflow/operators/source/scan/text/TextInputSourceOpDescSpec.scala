package edu.uci.ics.texera.workflow.operators.source.scan.text

import edu.uci.ics.texera.workflow.common.tuple.Tuple
import edu.uci.ics.texera.workflow.common.tuple.schema.{AttributeType, Schema}
import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AnyFlatSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.convert.ImplicitConversions.`list asScalaBuffer`

class TextInputSourceOpDescSpec extends AnyFlatSpec with BeforeAndAfter {
  var textInputSourceOpDesc: TextInputSourceOpDesc = _

  val TestTextFilePath: String = "src/test/resources/line_numbers.txt"
  val TestCRLFTextFilePath: String = "src/test/resources/line_numbers_crlf.txt"
  val StartOffset: Int = 0
  val EndOffset: Int = 5

  before {
    textInputSourceOpDesc = new TextInputSourceOpDesc()
  }

  it should "infer schema with single column representing each line of text in normal text scan mode" in {
    val inferredSchema: Schema = textInputSourceOpDesc.sourceSchema()

    assert(inferredSchema.getAttributes.length == 1)
    assert(inferredSchema.getAttribute("line").getType == AttributeType.STRING)
  }

  it should "infer schema with single column representing entire input in outputAsSingleTuple mode" in {
    val outputAsSingleTuple: Boolean = true
    textInputSourceOpDesc.outputAsSingleTuple = outputAsSingleTuple
    val inferredSchema: Schema = textInputSourceOpDesc.sourceSchema()

    assert(inferredSchema.getAttributes.length == 1)
    assert(inferredSchema.getAttribute("text").getType == AttributeType.STRING)
  }

  it should "infer schema with user-specified output schema attribute" in {
    val outputAsSingleTuple: Boolean = false
    textInputSourceOpDesc.outputAsSingleTuple = outputAsSingleTuple
    val customOutputAttributeName: String = "testing"
    textInputSourceOpDesc.attributeName = Option(customOutputAttributeName)
    val inferredSchema: Schema = textInputSourceOpDesc.sourceSchema()

    assert(inferredSchema.getAttributes.length == 1)
    assert(inferredSchema.getAttribute("testing").getType == AttributeType.STRING)
  }

  it should "read first 5 lines of the input text into corresponding output tuples" in {
    val outputAsSingleTuple: Boolean = false
    val inputString: String = readFileIntoString(TestTextFilePath)
    textInputSourceOpDesc.textInput = inputString
    textInputSourceOpDesc.outputAsSingleTuple = outputAsSingleTuple
    val textScanSourceOpExec =
      new TextInputSourceOpExec(textInputSourceOpDesc, StartOffset, EndOffset, "line")
    textScanSourceOpExec.open()
    val processedTuple: Iterator[Tuple] = textScanSourceOpExec.produceTexeraTuple()

    assert(processedTuple.next().getField("line").equals("line1"))
    assert(processedTuple.next().getField("line").equals("line2"))
    assert(processedTuple.next().getField("line").equals("line3"))
    assert(processedTuple.next().getField("line").equals("line4"))
    assert(processedTuple.next().getField("line").equals("line5"))
    assertThrows[java.util.NoSuchElementException](processedTuple.next().getField("line"))
    textScanSourceOpExec.close()
  }

  it should "read first 5 lines of the input text with CRLF separators into corresponding output tuples" in {
    val outputAsSingleTuple: Boolean = false
    val inputString: String = readFileIntoString(TestCRLFTextFilePath)
    textInputSourceOpDesc.textInput = inputString
    textInputSourceOpDesc.outputAsSingleTuple = outputAsSingleTuple
    val textScanSourceOpExec =
      new TextInputSourceOpExec(textInputSourceOpDesc, StartOffset, EndOffset, "line")
    textScanSourceOpExec.open()
    val processedTuple: Iterator[Tuple] = textScanSourceOpExec.produceTexeraTuple()

    assert(processedTuple.next().getField("line").equals("line1"))
    assert(processedTuple.next().getField("line").equals("line2"))
    assert(processedTuple.next().getField("line").equals("line3"))
    assert(processedTuple.next().getField("line").equals("line4"))
    assert(processedTuple.next().getField("line").equals("line5"))
    assertThrows[java.util.NoSuchElementException](processedTuple.next().getField("line"))
    textScanSourceOpExec.close()
  }

  it should "read first 5 lines of the input text into a single output tuple" in {
    val outputAsSingleTuple: Boolean = true
    val inputString: String = readFileIntoString(TestTextFilePath)
    textInputSourceOpDesc.textInput = inputString
    textInputSourceOpDesc.outputAsSingleTuple = outputAsSingleTuple
    val textScanSourceOpExec =
      new TextInputSourceOpExec(textInputSourceOpDesc, StartOffset, EndOffset, "text")
    textScanSourceOpExec.open()
    val processedTuple: Iterator[Tuple] = textScanSourceOpExec.produceTexeraTuple()

    assert(
      processedTuple
        .next()
        .getField("text")
        .equals("line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10")
    )
    assertThrows[java.util.NoSuchElementException](processedTuple.next().getField("text"))
    textScanSourceOpExec.close()
  }

  /**
    * Helper function using UTF-8 encoding to read text file
    * into String
    *
    * @param filePath
    * @return entire file represented as String
    */
  def readFileIntoString(filePath: String): String = {
    val path: Path = Paths.get(filePath)
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }
}
