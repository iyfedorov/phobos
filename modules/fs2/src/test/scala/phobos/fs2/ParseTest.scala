package phobos.fs2

import org.scalatest.wordspec.AsyncWordSpec
import phobos.decoding.XmlDecoder
import phobos.syntax.text
import phobos.fs2._
import fs2.Stream
import cats.effect.IO
import cats.effect.unsafe.{IORuntimeConfig, Scheduler, IORuntime}
import org.scalatest.Inspectors
import phobos.decoding.DecodingError
import phobos.derivation.semiauto.deriveXmlDecoder

class ParseTest extends AsyncWordSpec with Inspectors {
  val (scheduler, shutdown) = Scheduler.createDefaultScheduler()
  implicit val ioRuntime: IORuntime =
    IORuntime(executionContext, executionContext, scheduler, shutdown, IORuntimeConfig.apply())

  case class Foo(@text txt: Int)
  implicit val fooDecoder: XmlDecoder[Foo] = deriveXmlDecoder("foo")

  object xml {
    val simpleSequential =
      ("root" :: Nil) ->
        """|<root>
         |  <foo>1</foo>
         |  <foo>2</foo>
         |  <foo>3</foo>
         |  <foo>4</foo>
         |</root>
         |""".stripMargin

    val nestedRepetetive =
      ("root" :: "sub" :: Nil) ->
        """|<root>
         |  <sub>
         |    <foo>1</foo>
         |    <foo>2</foo>
         |  </sub>
         |  <sub>
         |    <foo>3</foo>
         |    <foo>4</foo>
         |  </sub>
         |</root>
         |""".stripMargin

    val nestedRepetetiveIcnludingOtherTags =
      ("root" :: "sub" :: Nil) ->
        """|<root>
         |  <sub>
         |    <foo>1</foo>
         |    <!-- skip it -->
         |    <bar>nope</bar>
         |    <foo>2</foo>
         |  </sub>
         |  <sub>
         |    <foo>3</foo>
         |    <foo>4</foo>
         |  </sub>
         |  <!-- skip it too -->
         |  <bar>nope</bar>
         |  <sub>
         |    <foo>5</foo>
         |  </sub>
         |  <sub>
         |    <!-- and this one -->
         |    <bar>nope</bar>
         |  </sub>
         |</root>
         |""".stripMargin

    val all = simpleSequential :: nestedRepetetive :: nestedRepetetiveIcnludingOtherTags :: Nil
  }

  val expectations = Map(
    xml.simpleSequential -> Vector(1, 2, 3, 4).map(Foo(_)).map(Right(_)),
    xml.nestedRepetetive -> Vector(1, 2, 3, 4).map(Foo(_)).map(Right(_)),
    xml.nestedRepetetiveIcnludingOtherTags -> Vector(
      Right(Foo(1)),
      Left(DecodingError("Invalid local name. Expected 'foo', but found 'bar'", List("bar", "sub", "root"), None)),
      Right(Foo(2)),
      Right(Foo(3)),
      Right(Foo(4)),
      Right(Foo(5)),
      Left(DecodingError("Invalid local name. Expected 'foo', but found 'bar'", List("bar", "sub", "root"), None)),
    ),
  )

  def readAtOnce(path: List[String], xmlString: String) = {
    val streamBuilder = path.tail.foldLeft(Parse.oneDocument(path.head))(_.inElement(_))
    Stream.emits[IO, Byte](xmlString.getBytes).through(streamBuilder.everyElementAs[Foo].toFs2Stream[IO])
  }

  def readByteByByte(path: List[String], xmlString: String) = {
    val streamBuilder = path.tail.foldLeft(Parse.oneDocument(path.head))(_.inElement(_))
    Stream
      .emits[IO, Byte](xmlString.getBytes)
      .chunkLimit(1)
      .unchunks
      .through(streamBuilder.everyElementAs[Foo].toFs2Stream[IO])
  }

  def assertStreamResult(stream: Stream[IO, Either[DecodingError, Foo]])(expects: Vector[Either[DecodingError, Foo]]) =
    stream.compile.toVector.map(result => assert(result === expects)).unsafeToFuture()

  "Parse" should {
    "handle all the elements" when {
      "oneDocument called with simpleSequential xml" in {
        val testCase @ (path, xmlString) = xml.simpleSequential

        for {
          r1 <- assertStreamResult(readAtOnce(path, xmlString))(expectations(testCase))
          r2 <- assertStreamResult(readByteByByte(path, xmlString))(expectations(testCase))
        } yield succeed
      }

      "oneDocument called with nestedRepetetive xml" in {
        val testCase @ (path, xmlString) = xml.nestedRepetetive

        for {
          r1 <- assertStreamResult(readAtOnce(path, xmlString))(expectations(testCase))
          r2 <- assertStreamResult(readByteByByte(path, xmlString))(expectations(testCase))
        } yield succeed
      }

      "oneDocument called with nestedRepetetiveIcnludingOtherTags xml" in {
        val testCase @ (path, xmlString) = xml.nestedRepetetiveIcnludingOtherTags

        for {
          r1 <- assertStreamResult(readAtOnce(path, xmlString))(expectations(testCase))
          r2 <- assertStreamResult(readByteByByte(path, xmlString))(expectations(testCase))
        } yield succeed
      }
    }

  }
}
