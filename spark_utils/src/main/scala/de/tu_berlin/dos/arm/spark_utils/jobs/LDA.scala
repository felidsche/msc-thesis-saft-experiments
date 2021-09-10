package de.tu_berlin.dos.arm.spark_utils.jobs

import org.apache.spark.ml.feature.{CountVectorizer, CountVectorizerModel, RegexTokenizer, StopWordsRemover}
import org.apache.spark.ml.linalg.{Vector => MLVector}
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.mllib.clustering.{DistributedLDAModel, EMLDAOptimizer, LDA, LDAModel}
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.{SparkConf, SparkContext}
import org.rogach.scallop.exceptions.ScallopException
import org.rogach.scallop.{ScallopConf, ScallopOption}

import java.text.SimpleDateFormat
import java.util.Calendar

object LDA {

  def main(args: Array[String]): Unit = {
    // init spark
    val spark = SparkSession
      .builder
      .appName("LDA")
      .getOrCreate()

    val sparkConf = new SparkConf()
      .setAppName("LDA")
      .setMaster("local") // TODO: remove before cluter execution

    val sparkContext = new SparkContext(sparkConf)

    val form = new SimpleDateFormat("dd.MM.yyyy_HH:MM:SS");
    val execCal = Calendar.getInstance
    val executionDate = form.format(execCal.getTime);


    sparkContext.setCheckpointDir("../checkpoints/LDA/" + executionDate + "/")

    val conf = new LDAArgs(args)
    // ?
    import spark.implicits._
    val stopWords: Array[String] = sparkContext.textFile(path = conf.inputStopWords()).collect().flatMap(_.stripMargin.split("\\s+"))
    val source: DataFrame = sparkContext.textFile(path = conf.inputCorpus()).toDF("docs")
    val tokenizer: RegexTokenizer = new RegexTokenizer().setInputCol("docs").setOutputCol("rawTokens")
    val stopWordsRemover: StopWordsRemover = new StopWordsRemover().setInputCol("rawTokens").setOutputCol("tokens")


    stopWordsRemover.setStopWords(stopWordsRemover.getStopWords ++ stopWords)
    val countVectorizer: CountVectorizer = new CountVectorizer()
      .setVocabSize(10000)
      .setInputCol("tokens")
      .setOutputCol("features")
    val pipeline: Pipeline = new Pipeline().setStages(Array(tokenizer, stopWordsRemover, countVectorizer))

    val model: PipelineModel = pipeline.fit(source)
    val corpus: RDD[(Long, Vector)] = model.transform(source)
      .select("features")
      .rdd
      .map { case Row(features: MLVector) => Vectors.fromML(features) }
      .zipWithIndex()
      .map(_.swap)
    val vocabArray: Array[String] = model.stages(2).asInstanceOf[CountVectorizerModel].vocabulary
    val actualNumTokens: Long = corpus.map(_._2.numActives).sum().toLong
    println(s"actualNumTokens: $actualNumTokens")

    val lda = new LDA()
      .setCheckpointInterval(conf.checkpointInterval()) // defines after how many iterations to checkpoint)
    val optimizer = new EMLDAOptimizer

    lda.setOptimizer(optimizer)
      .setK(conf.k())
      .setMaxIterations(conf.iterations())

    val ldaModel: LDAModel = lda.run(corpus)


    val distLDAModel = ldaModel.asInstanceOf[DistributedLDAModel]
    val avgLogLikelihood = distLDAModel.logLikelihood / corpus.count().toDouble
    println(s"avgLogLikelihood: $avgLogLikelihood")
    val topicIndices = ldaModel.describeTopics(maxTermsPerTopic = 10)

    val topics = topicIndices.map { case (terms, termWeights) =>
      terms.zip(termWeights).map { case (term, weight) => (vocabArray(term.toInt), weight) }
    }
    topics
      .zipWithIndex
      .foreach { case (topic, i) =>
        println(s"TOPIC $i")
        topic.foreach { case (term, weight) =>
          println(s"$term\t$weight")
        }
        println()
      }
    sparkContext.stop()
  }
}

class LDAArgs(a: Seq[String]) extends ScallopConf(a) {
  val inputCorpus: ScallopOption[String] = trailArg[String](required = true, name = "<input>",
    descr = "Input corpus file").map(_.toLowerCase)

  val inputStopWords: ScallopOption[String] = trailArg[String](required = true, name = "<input>",
    descr = "Input stopwords file").map(_.toLowerCase)

  val k: ScallopOption[Int] = opt[Int](required = true, descr = "Amount of clusters")
  val iterations: ScallopOption[Int] = opt[Int](noshort = true, default = Option(100),
    descr = "Amount of LDA iterations")

  val checkpointInterval: ScallopOption[Int] = opt[Int](noshort = true, default = Option(10),
    descr = "Interval of checkpoints")

  override def onError(e: Throwable): Unit = e match {
    case ScallopException(message) =>
      println(message)
      println()
      printHelp()
      System.exit(1)
    case other => super.onError(e)
  }

  verify()
}
