package org.broadinstitute.sting.queue.qscripts.performance

import org.broadinstitute.sting.queue.QScript
import org.broadinstitute.sting.queue.extensions.gatk._
import java.lang.Math
import org.broadinstitute.sting.utils.baq.BAQ.CalculationMode
import org.broadinstitute.sting.utils.PathUtils
import org.broadinstitute.sting.queue.function.QFunction
import org.broadinstitute.sting.queue.engine.JobRunInfo

class GATKPerformanceOverTime extends QScript {
  val STD_RESULTS_DIR = "/humgen/gsa-hpprojects/dev/depristo/oneOffProjects/gatkPerformanceOverTime"

  @Argument(shortName = "results", doc="results", required=false)
  val resultsDir: File = new File("runResults")

  @Argument(shortName = "test", doc="test", required=false)
  val TEST: Boolean = false

  @Argument(shortName = "resources", doc="resources", required=true)
  val resourcesDir: String = ""

  @Argument(shortName = "myJarFile", doc="Path to the current GATK jar file", required=true)
  val myJarFile: File = null

  @Argument(shortName = "iterations", doc="it", required=false)
  val iterations: Int = 3

  @Argument(shortName = "assessment", doc="Which assessments should we run?", required=false)
  val assessmentsArg: Set[String] = Assessment.values map(_.toString)

  val nIterationsForSingleTestsPerIteration: Int = 3

  @Argument(shortName = "ntTest", doc="For each value provided we will use -nt VALUE in the multi-threaded tests", required=false)
  //val ntTests: List[Int] = List(4, 6, 8, 10, 12, 16, 24, 32)
  val ntTests: List[Int] = List(1, 2, 3, 4, 6, 8, 10, 12, 16, 20, 24)

  @Argument(shortName = "steps", doc="steps", required=false)
  val steps: Int = 10

  @Argument(shortName = "maxNSamples", doc="maxNSamples", required=false)
  val maxNSamples: Int = 1000000

  val RECAL_BAM_FILENAME = "NA12878.HiSeq.WGS.bwa.cleaned.recal.hg19.20.20GAV.8.bam"
  val dbSNP_FILENAME = "dbsnp_132.b37.vcf"
  val BIG_VCF_WITH_GENOTYPES = "ALL.chr22.phase1_release_v3.20101123.snps_indels_svs.genotypes.vcf.gz"
  val RECAL_FILENAME = "NA12878.HiSeq.WGS.bwa.cleaned.recal.hg19.20.csv"  // TODO -- update to use recal table for BQSRv2
  val b37_FILENAME = "human_g1k_v37.fasta"

  def makeResource(x: String): File = new File("%s/%s".format(resourcesDir, x))
  def makeChunk(x: Int): File = makeResource("chunk_%d.vcf".format(x))
  def COMBINE_FILES: List[File] = Range(1,10).map(makeChunk).toList

  class AssessmentParameters(val name: String, val bamList: File, val intervals: File, val nSamples: Int, val dcov: Int, val baq: Boolean)

  // TODO -- count the number of lines in the bam.list file
  val WGSAssessment = new AssessmentParameters("WGS.multiSample.4x", "wgs.bam.list.local.list", "wgs.bam.list.intervals", 1103, 50, true)
  val WGSDeepAssessment = new AssessmentParameters("WGS.singleSample.60x", "wgs.deep.bam.list.local.list", "wgs.deep.bam.list.intervals", 1, 250, false)
  val WExAssessment = new AssessmentParameters("WEx.multiSample.150x", "wex.bam.list.local.list", "wex.bam.list.intervals", 140, 500, true)

  val dataSets = List(WGSAssessment, WGSDeepAssessment, WExAssessment)

  val GATK_RELEASE_DIR = new File("/humgen/gsa-hpprojects/GATK/bin/")
  val GATKs: Map[String, File] = Map(
    "v2.cur" -> myJarFile, // TODO -- how do I get this value?
    "v1.6" -> findMostRecentGATKVersion("1.6"))

  object Assessment extends Enumeration {
    type Assessment = Value
    val UG, UG_NT, CL, CV, CV_NT, VE, VE_NT, SV, BQSR_NT = Value
  }

  trait UNIVERSAL_GATK_ARGS extends CommandLineGATK {
    this.logging_level = "INFO"
    this.reference_sequence = makeResource(b37_FILENAME)
    this.memoryLimit = 4
  }

  def script() {
    val assessments = assessmentsArg.map(Assessment.withName(_))

    if ( ! resultsDir.exists ) resultsDir.mkdirs()

    for ( iteration <- 0 until iterations ) {
      for ( assess <- dataSets ) {
        for (nSamples <- divideSamples(assess.nSamples) ) {
          val sublist = new SliceList(assess.name, nSamples, makeResource(assess.bamList))
          if ( iteration == 0 ) add(sublist) // todo - remove condition when Queue bug is fixed
          for ( (gatkName, gatkJar) <- GATKs ) {
            val name: String = "assess.%s_gatk.%s_iter.%d".format(assess.name, gatkName, iteration)

            trait VersionOverrides extends CommandLineGATK {
              this.jarFile = gatkJar
              this.dcov = assess.dcov

              // special handling of test intervals
              if ( TEST )
                this.intervalsString :+= "20:10,000,000-10,001,000"
              else
                this.intervals :+= makeResource(assess.intervals)

              this.configureJobReport(Map(
                "iteration" -> iteration,
                "gatk" -> gatkName,
                "nSamples" -> nSamples,
                "assessment" -> assess.name))
            }

            // SNP calling
            if ( assessments.contains(Assessment.UG) )
              add(new Call(sublist.list, nSamples, name, assess.baq) with VersionOverrides)
            if ( assessments.contains(Assessment.UG_NT) && nSamples == assess.nSamples )
              addMultiThreadedTest(() => new Call(sublist.list, nSamples, name, assess.baq) with VersionOverrides)

            // CountLoci
            if ( assessments.contains(Assessment.CL) ) {
              add(new MyCountLoci(sublist.list, nSamples, name) with VersionOverrides)
              if ( nSamples == assess.nSamples )
                addMultiThreadedTest(() => new MyCountLoci(sublist.list, nSamples, name) with VersionOverrides)
            }
          }
        }
      }

      // GATK v2 specific tests
      for ( iteration <- 0 until iterations ) {
        for ( (gatkName, gatkJar) <- GATKs ) {
          if ( gatkName.contains("v2") ) {
            if ( assessments.contains(Assessment.CV_NT) ) {
              for ( outputBCF <- List(true, false) ) {
                val outputName = if ( outputBCF ) "bcf" else "vcf"

                def makeCV(): CommandLineGATK = {
                  val CV = new CombineVariants with UNIVERSAL_GATK_ARGS
                  CV.configureJobReport(Map( "iteration" -> iteration, "gatk" -> gatkName,
                    "output" -> outputName, "assessment" -> "CombineVariants.nt"))
                  CV.jarFile = gatkJar
                  CV.intervalsString :+= "22"
                  CV.variant = List(makeResource(BIG_VCF_WITH_GENOTYPES))
                  CV.out = new File("/dev/null")
                  CV.bcf = outputBCF
                  CV
                }

                addMultiThreadedTest(makeCV)
              }
            }

            if ( assessments.contains(Assessment.BQSR_NT) ) {
              def makeBQSR(): CommandLineGATK = {
                val BQSR = new BaseRecalibrator with UNIVERSAL_GATK_ARGS
                BQSR.configureJobReport(Map( "iteration" -> iteration, "gatk" -> gatkName, "assessment" -> "BQSR.nt"))
                BQSR.jarFile = gatkJar
                BQSR.intervalsString :+= "20"
                BQSR.knownSites :+= makeResource(dbSNP_FILENAME)
                //this.covariate ++= List("ReadGroupCovariate", "QualityScoreCovariate", "CycleCovariate", "ContextCovariate")
                BQSR.input_file :+= makeResource(RECAL_BAM_FILENAME)
                BQSR.out = new File("/dev/null")
                BQSR.no_plots = true
                BQSR
              }

              addMultiThreadedTest(makeBQSR)
            }
          }
        }
      }

      for ( subiteration <- 0 until nIterationsForSingleTestsPerIteration ) {
        for ( (gatkName, gatkJar) <- GATKs ) {
          { // Standard VCF tools
          trait VersionOverrides extends CommandLineGATK {
            this.jarFile = gatkJar
            this.configureJobReport(Map( "iteration" -> iteration, "gatk" -> gatkName))
          }

            val CV = new CombineVariants with UNIVERSAL_GATK_ARGS with VersionOverrides
            CV.variant = COMBINE_FILES
            CV.intervalsString = (if ( TEST ) List("1:10,000,000-10,010,000") else List("1", "2", "3", "4", "5"))
            CV.out = new File("/dev/null")
            if ( assessments.contains(Assessment.CV) )
              add(CV)

            val SV = new SelectVariants with UNIVERSAL_GATK_ARGS with VersionOverrides
            SV.variant = makeResource("chunk_1.vcf")
            SV.sample_name = List("HG00096") // IMPORTANT THAT THIS SAMPLE BE IN CHUNK ONE
            if ( TEST ) SV.intervalsString = List("1:10,000,000-10,010,000")
            SV.out = new File("/dev/null")
            if ( assessments.contains(Assessment.SV) )
              add(SV)

            def makeVE(): CommandLineGATK = {
              val VE = new VariantEval with UNIVERSAL_GATK_ARGS with VersionOverrides
              VE.eval :+= makeResource("chunk_1.vcf")
              if ( TEST ) VE.intervalsString = List("1:10,000,000-10,010,000")
              VE.out = new File("/dev/null")
              VE.comp :+= new TaggedFile(makeResource(dbSNP_FILENAME), "dbSNP")
              VE
            }

            if ( assessments.contains(Assessment.VE) ) {
              add(makeVE())
            }

            if ( assessments.contains(Assessment.VE_NT) && subiteration == 0 )
              addMultiThreadedTest(makeVE)
          }
        }
      }
    }
  }

  def addMultiThreadedTest(makeCommand: () => CommandLineGATK) {
    if ( ntTests.size > 1 ) {
      for ( nt <- ntTests ) {
        val cmd = makeCommand()
        cmd.nt = nt
        cmd.memoryLimit = cmd.memoryLimit * (if ( nt >= 8 ) (if (nt>=16) 4 else 2) else 1)
        cmd.addJobReportBinding("nt", nt)
        cmd.analysisName = cmd.analysisName + ".nt"
        add(cmd)
      }
    }
  }

  def divideSamples(nTotalSamples: Int): List[Int] = {
    val maxLog10: Double = Math.log10(Math.min(maxNSamples, nTotalSamples))
    val stepSize: Double = maxLog10 / steps
    val ten: Double = 10.0
    def deLog(x: Int): Int = Math.round(Math.pow(ten, stepSize * x)).toInt
    dedupe(Range(0, steps+1).map(deLog).toList)
  }

  class Call(@Input(doc="foo") bamList: File, n: Int, name: String, useBaq: Boolean) extends UnifiedGenotyper with UNIVERSAL_GATK_ARGS {
    this.input_file :+= bamList
    this.stand_call_conf = 10.0
    this.o = outVCF
    this.baq = if ( useBaq ) org.broadinstitute.sting.utils.baq.BAQ.CalculationMode.RECALCULATE else org.broadinstitute.sting.utils.baq.BAQ.CalculationMode.OFF
    @Output(doc="foo") var outVCF: File = new File("/dev/null")
  }

  class MyCountLoci(@Input(doc="foo") bamList: File, n: Int, name: String) extends CountLoci with UNIVERSAL_GATK_ARGS {
    this.input_file :+= bamList
    @Output(doc="foo") var outFile: File = new File("/dev/null")
    this.o = outFile
  }

  class SliceList(prefix: String, n: Int, @Input bamList: File) extends CommandLineFunction {
    this.analysisName = "SliceList"
    @Output(doc="foo") var list: File = new File("%s/%s.bams.%d.list".format(resultsDir.getPath, prefix, n))
    def commandLine = "head -n %d %s | awk '{print \"%s/\" $1}' > %s".format(n, bamList, resourcesDir, list)
  }

  def dedupe(elements:List[Int]):List[Int] = {
    if (elements.isEmpty)
      elements
    else
      elements.head :: dedupe(for (x <- elements.tail if x != elements.head) yield x)
  }

  /**
   * Walk over the GATK released directories to find the most recent JAR files corresponding
   * to the version prefix.  For example, providing input "GenomeAnalysisTK-1.2" will
   * return the full path to the most recent GenomeAnalysisTK.jar in the GATK_RELEASE_DIR
   * in directories that match GATK_RELEASE_DIR/GenomeAnalysisTK-1.2*
   */
  def findMostRecentGATKVersion(version: String): File = {
    PathUtils.findMostRecentGATKVersion(GATK_RELEASE_DIR, version)
  }
}