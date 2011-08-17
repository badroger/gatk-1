package org.broadinstitute.sting.gatk.walkers.misc.intronloss;

import net.sf.picard.fastq.FastqRecord;
import net.sf.picard.fastq.FastqWriter;
import org.broad.tribble.Feature;
import org.broadinstitute.sting.commandline.Argument;
import org.broadinstitute.sting.commandline.RodBinding;
import org.broadinstitute.sting.gatk.GenomeAnalysisEngine;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.refdata.features.refseq.RefSeqFeature;
import org.broadinstitute.sting.gatk.walkers.RefWalker;
import org.broadinstitute.sting.utils.BaseUtils;
import org.broadinstitute.sting.utils.GenomeLoc;
import org.broadinstitute.sting.utils.MathUtils;
import org.broadinstitute.sting.utils.QualityUtils;
import org.broadinstitute.sting.utils.collections.Pair;
import org.broadinstitute.sting.utils.exceptions.UserException;

import javax.print.DocFlavor;
import javax.sound.midi.Sequence;
import java.io.File;
import java.security.PrivateKey;
import java.util.List;

/**
 * Simulates reads from an intron deletion (or not)
 */
public class IntronLossSequenceSimulator extends RefWalker<Pair<Byte,Boolean>,Pair<StringBuffer,StringBuffer>> {

    @Argument(shortName = "rs", fullName = "refSeq", doc = "RefGene file", required = true)
    public RodBinding<RefSeqFeature> refSeqRodBinding;

    @Argument(shortName = "fq",fullName = "outputFASTQ", doc = "the output FASTQ file", required = true)
    public File outputFastQ;

    @Argument(fullName = "ploidy", required = false, doc = "")
    public int ploidy = 2;

    @Argument(fullName = "nVar", required = false, doc = "")
    public int nVar = 1;

    @Argument(fullName = "sequencePrefix", required = false, doc = "")
    public String seqPrefix = "ILSS-SRR";

    private static final int AVG_CVG_PER_CHR_POISSON = 10;
    private static final int READ_SIZE_BP = 76;

    private FastqWriter writer;

    public void initialize() {
        writer = new FastqWriter(outputFastQ);
    }

    public Pair<StringBuffer,StringBuffer> reduceInit() {
        return new Pair<StringBuffer, StringBuffer>(new StringBuffer(), new StringBuffer());
    }

    public boolean isReduceByInterval() { return true; }

    public Pair<StringBuffer,StringBuffer> reduce(Pair<Byte,Boolean> map, Pair<StringBuffer,StringBuffer> reduce) {
        if ( map == null || reduce == null ) {
            return reduce;
        }

        if ( map.second ) {
            // if in an exon
            reduce.first.append((char) map.first.byteValue());
        }

        reduce.second.append((char) map.first.byteValue());

        return reduce;
    }

    public Pair<Byte,Boolean> map(RefMetaDataTracker tracker, ReferenceContext ref, AlignmentContext context) {
        if ( tracker == null || ! tracker.hasValues(refSeqRodBinding) ) {
            return new Pair<Byte, Boolean>(ref.getBase(),true);
        }

        //RefSeqFeature geneFeature = getProperFeature(tracker.getValues(refSeqRodBinding));
        RefSeqFeature geneFeature = tracker.getFirstValue(refSeqRodBinding);
        if ( geneFeature == null ) {
            return new Pair<Byte, Boolean>(ref.getBase(),true);
        }

        // todo: probably cache this
        List<GenomeLoc> exons = geneFeature.getExons();
        boolean inExon = false;
        for ( GenomeLoc exon : exons ) {
            inExon |= ref.getLocus().overlapsP(exon);
        }

        return new Pair<Byte, Boolean>(ref.getBase(),inExon);
    }

    private RefSeqFeature getProperFeature(List<RefSeqFeature> refSeqRawFeatures) {
        for ( Feature f : refSeqRawFeatures ) {
            if ( f.getClass().isAssignableFrom(RefSeqFeature.class) ) {
                return (RefSeqFeature) f;
            }
        }

        return null;
    }

    public void onTraversalDone(Pair<StringBuffer,StringBuffer> pair) { }

    public void onTraversalDone(List<Pair<GenomeLoc,Pair<StringBuffer,StringBuffer>>> sequenceByGene) {
        for ( Pair<GenomeLoc,Pair<StringBuffer,StringBuffer>> genePair : sequenceByGene ) {
            String intronLossStr = genePair.second.first.toString();
            String normalStr = genePair.second.first.toString();
            // todo -- generalize random process (uniform/poisson/etc)
            simulateSequencing(intronLossStr, normalStr, nVar, ploidy);
        }
    }

    public void simulateSequencing(String loss, String normal, int nVar, int ploidy) {
        // what is the size of the read pool (normal)
        int nReadPool = normal.length()-READ_SIZE_BP+1;
        // now what is the probability p with which we uniformly select each read to obtain
        // the desired expected coverage, we have that coverage at a specific site is READ_SIZE_BP copies
        // of the random variable, so it's CVG*SEQ_SIZE = p*2*READ_SIZE*READ_POOL -- the factor of 2 is due to pairs
        double pSeq = ( (double) AVG_CVG_PER_CHR_POISSON*normal.length())/( (double) READ_SIZE_BP*nReadPool*2 );

        if ( pSeq > 1.0 ) {
            throw new UserException("Cannot simulate to given coverage without introducing duplication");
        }

        int outputSequences = 0;

        for ( int offset = 0; offset < normal.length()-READ_SIZE_BP; offset++ ) {
            for ( int normChrom = 0; normChrom < ploidy-nVar; normChrom++ ) {
                if ( GenomeAnalysisEngine.getRandomGenerator().nextDouble() < pSeq ) {
                    FastqRecord[] records = generateFastqFromSequence(normal, offset,outputSequences);
                    for ( FastqRecord record : records ) {
                        writer.write(record);
                    }
                    outputSequences ++;
                }
            }
        }

        for ( int offset = 0; offset < loss.length()-READ_SIZE_BP; offset++) {
            for ( int varChrom = 0; varChrom < nVar; varChrom++ ) {
                FastqRecord[] records = generateFastqFromSequence(loss,offset,outputSequences);
                for ( FastqRecord record : records ) {
                    writer.write(record);
                }
                outputSequences++;
            }
        }
    }

    private FastqRecord[] generateFastqFromSequence(String seq, int offset, int seqOutput) {
        FastqRecord[] toRet;
        int insert = generateInsertSize();
        boolean widowed = offset + insert < 0 || offset + insert > seq.length();
        String readName = String.format("%s:%d%s",seqPrefix,seqOutput, widowed ? "" : " 1:Y:0:ATGCGC");
        Pair<String,String> readSequence = generateReadSequence(seq,offset,insert<0);
        FastqRecord record = new FastqRecord(readName,readSequence.first,readName,readSequence.second);
        if ( widowed ) {
            return new FastqRecord[]{ record };
        }

        String mateName =  String.format("%s:%d%s",seqPrefix,seqOutput, "2:Y:0:ATGCGC");
        Pair<String,String> mateSequence = generateReadSequence(seq,offset+insert,insert<0);
        FastqRecord mateRecord = new FastqRecord(mateName,mateSequence.first,mateName,mateSequence.second);

        return new FastqRecord[]{ record, mateRecord };
    }

    private int generateInsertSize() {
        // todo -- be able to read this from a file
        int symmetricUnderlying = (int)( 260.5 +  GenomeAnalysisEngine.getRandomGenerator().nextGaussian()*30);
        int chiSquareAdd = (int) ( 20.5 + Math.pow(GenomeAnalysisEngine.getRandomGenerator().nextGaussian()*30,2) );
        return symmetricUnderlying + chiSquareAdd;
    }

    private Pair<String,String> generateReadSequence(String base, int offset, boolean reverseComplement) {
        // todo -- make the random process more real (investigate using site qualities and TT)
        // todo -- incorporate indels as a possibility
        String rawSeq = base.substring(offset,offset+READ_SIZE_BP);
        StringBuffer newSeq = new StringBuffer();
        StringBuffer qualSeq = new StringBuffer();
        for ( char b : rawSeq.toCharArray() ) {
            // qual: normal with mean 25, SD 10, quantized by rounding to nearest number
            byte qual = (byte) ( (int) 25.5 + GenomeAnalysisEngine.getRandomGenerator().nextGaussian()*10 );
            char observedB;
            if ( GenomeAnalysisEngine.getRandomGenerator().nextDouble() < QualityUtils.qualToErrorProb(qual) ) {
                observedB = (char) BaseUtils.baseIndexToSimpleBase(BaseUtils.getRandomBaseIndex(BaseUtils.simpleBaseToBaseIndex((byte) b)));
            } else {
                observedB = b;
            }
            newSeq.append(observedB);
            qualSeq.append((char) (qual + 33));
        }

        String newSeqStr = newSeq.toString();
        String qualSeqStr = qualSeq.toString();
        if ( reverseComplement ) {
            newSeqStr = BaseUtils.simpleReverseComplement(newSeq.toString());
            qualSeqStr = new StringBuffer(qualSeqStr).reverse().toString();
        }

        return new Pair<String, String>(newSeqStr,qualSeqStr);
    }
}
