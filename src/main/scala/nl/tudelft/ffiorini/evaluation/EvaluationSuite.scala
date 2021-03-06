package nl.tudelft.ffiorini.experiments

import org.apache.arrow.parquet.ParquetToArrowConverter
import org.apache.arrow.vector.ValueVector
import org.apache.spark.ArrowSparkContext

import java.nio.charset.StandardCharsets

object EvaluationSuite {

  def wordCount(sc : ArrowSparkContext) : Unit = {
    println("WORDCOUNT EXAMPLE")
    val numPart = 10

    val textRDD = sc.textFile("data/example-10m.txt", numPart)
    textRDD.first()
    val narrTr = textRDD.flatMap(line => line.split(" ")).map(word => (word, 1))
    narrTr.first()
    val wideTr = narrTr.reduceByKey(_ + _)
    wideTr.first()

    val handler = new ParquetToArrowConverter
    handler.process("data/people10m.parquet")
    val binArr = Array[ValueVector](handler.getVariableWidthVector.get())
    val binRDD = sc.makeArrowRDD[Array[Byte]](binArr, numPart)
    binRDD.first()
    val binNarrTr = binRDD.map(b => new String(b, StandardCharsets.UTF_8)).map(word => (word, 1))
    binNarrTr.first()
    val binWideTr = binNarrTr.reduceByKey(_ + _)
    binWideTr.first()

    println("END OF WORDCOUNT EXAMPLE")
  }

  def scalaSort(sc: ArrowSparkContext) : Unit = {
    println("SCALASORT EXAMPLE")
    val numPart = 10

    val textRDD = sc.textFile("data/example-10m.txt", numPart)
    textRDD.first()
    val narrTr = textRDD.map((_, 1))
    narrTr.first()
    val wideTr = narrTr.sortByKey(true).map(_._1)
    wideTr.first()

    val handler = new ParquetToArrowConverter
    handler.process("data/people10m.parquet")
    val binArr = Array[ValueVector](handler.getVariableWidthVector.get())
    val binRDD = sc.makeArrowRDD[Array[Byte]](binArr, numPart)
    binRDD.first()
    val binNarrTr = binRDD.map(b => new String(b, StandardCharsets.UTF_8)).map((_, 1))
    binNarrTr.first()
    val binWideTr = binNarrTr.sortByKey(true).map(_._1)
    binWideTr.first()

    println("END OF SCALASORT EXAMPLE")
  }

  def minimumValue(sc: ArrowSparkContext) : Unit = {
    println("MINVALUE EXAMPLE")
    val numPart = 10

    val intRDDPar = sc.parallelize(Range(0, 10000000, 1), numPart)

    val handler = new ParquetToArrowConverter
    handler.process("data/numbers-10m.parquet")
    val intArr = Array[ValueVector](handler.getIntVector.get())
    val intRDD = sc.makeArrowRDD[Int](intArr, numPart)

    intRDDPar.min()

    intRDD.min()

    val t0 = System.nanoTime()
    intRDD.vectorMin()
    val t1 = System.nanoTime()
    println("Time: %04.3f".format((t1-t0)/1e9d))
  }

  def transformations(sc: ArrowSparkContext) : Unit = {

  }
}