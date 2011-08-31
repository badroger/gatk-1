/*
 * Copyright (c) 2011 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.gatk.walkers.haplotypecaller;

import net.sf.samtools.CigarElement;
import net.sf.samtools.SAMRecord;
import org.broadinstitute.sting.gatk.io.StingSAMFileWriter;
import org.broadinstitute.sting.utils.GenomeLoc;
import org.broadinstitute.sting.utils.SWPairwiseAlignment;
import org.broadinstitute.sting.utils.collections.Pair;
import org.broadinstitute.sting.utils.exceptions.ReviewedStingException;
import org.broadinstitute.sting.utils.variantcontext.Allele;
import org.broadinstitute.sting.utils.variantcontext.Genotype;
import org.broadinstitute.sting.utils.variantcontext.InferredGeneticContext;
import org.broadinstitute.sting.utils.variantcontext.VariantContext;

import java.util.*;

public class GenotypingEngine {

    // Smith-Waterman parameters copied from IndelRealigner
    private static final double SW_MATCH = 23.0;      // 1.0;
    private static final double SW_MISMATCH = -8.0;  //-1.0/3.0;
    private static final double SW_GAP = -14.0;       //-1.0-1.0/3.0;
    private static final double SW_GAP_EXTEND = -1.5; //-1.0/.0;

    public List<VariantContext> alignAndGenotype( final Pair<Haplotype, Haplotype> bestTwoHaplotypes, final byte[] ref, final GenomeLoc loc ) {
        final SWPairwiseAlignment swConsensus1 = new SWPairwiseAlignment( ref, bestTwoHaplotypes.first.bases, SW_MATCH, SW_MISMATCH, SW_GAP, SW_GAP_EXTEND );
        final SWPairwiseAlignment swConsensus2 = new SWPairwiseAlignment( ref, bestTwoHaplotypes.second.bases, SW_MATCH, SW_MISMATCH, SW_GAP, SW_GAP_EXTEND );

        System.out.println( bestTwoHaplotypes.first.toString() );
        System.out.println( "Cigar = " + swConsensus1.getCigar() );
        final List<VariantContext> vcs1 = generateVCsFromAlignment( swConsensus1, ref, bestTwoHaplotypes.first.bases, loc );

        System.out.println( bestTwoHaplotypes.second.toString() );
        System.out.println( "Cigar = " + swConsensus2.getCigar() );
        final List<VariantContext> vcs2 = generateVCsFromAlignment( swConsensus2, ref, bestTwoHaplotypes.second.bases, loc );

        return genotype( vcs1, vcs2 );
    }

    private static List<VariantContext> generateVCsFromAlignment( final SWPairwiseAlignment swConsensus, final byte[] ref, final byte[] read, final GenomeLoc loc ) {
        final ArrayList<VariantContext> vcs = new ArrayList<VariantContext>();

        int refPos = swConsensus.getAlignmentStart2wrt1();
        int readPos = 0;
        final int lookAhead = 5;

        for( final CigarElement ce : swConsensus.getCigar().getCigarElements() ) {
            final int elementLength = ce.getLength();
            switch( ce.getOperator() ) {
                case I:
                {
                    byte[] insertionBases = Arrays.copyOfRange( read, readPos, readPos + elementLength);
                    boolean allN = true;
                    for( byte b : insertionBases ) {
                        if( b != (byte) 'N' ) {
                            allN = false;
                            break;
                        }
                    }
                    if( !allN ) {
                        ArrayList<Allele> alleles = new ArrayList<Allele>();
                        alleles.add( Allele.create(Allele.NULL_ALLELE_STRING, true));
                        alleles.add( Allele.create(insertionBases, false));
                        System.out.println("> Insertion: " + alleles);
                        vcs.add(new VariantContext("HaplotypeCaller", loc.getContig(), loc.getStart() + refPos - 1, loc.getStart() + refPos - 1, alleles, VariantContext.NO_GENOTYPES, InferredGeneticContext.NO_NEG_LOG_10PERROR, null, null, ref[refPos-1]));
                    }
                    readPos += elementLength;
                    break;
                }
                case S:
                {
                    readPos += elementLength;
                    refPos += elementLength;
                    break;
                }
                case D:
                {
                    byte[] deletionBases = Arrays.copyOfRange( ref, refPos, refPos + elementLength);
                    ArrayList<Allele> alleles = new ArrayList<Allele>();
                    alleles.add( Allele.create(deletionBases, true) );
                    alleles.add( Allele.create(Allele.NULL_ALLELE_STRING, false) );
                    System.out.println( "> Deletion: " + alleles);
                    vcs.add( new VariantContext("HaplotypeCaller", loc.getContig(), loc.getStart() + refPos - 1, loc.getStart() + refPos + elementLength - 1, alleles, VariantContext.NO_GENOTYPES, InferredGeneticContext.NO_NEG_LOG_10PERROR, null, null, ref[refPos-1]) );
                    refPos += elementLength;
                    break;
                }
                case M:
                {
                    int numSinceMismatch = -1;
                    int stopOfMismatch = -1;
                    int startOfMismatch = -1;
                    int refPosStartOfMismatch = -1;
                    for( int iii = 0; iii < elementLength; iii++ ) {
                        if( ref[refPos] != read[readPos] && read[readPos] != ((byte) 'N') ) {
                            // SNP or start of possible MNP
                            if( stopOfMismatch == -1 ) {
                                startOfMismatch = readPos;
                                stopOfMismatch = readPos;
                                numSinceMismatch = 0;
                                refPosStartOfMismatch = refPos;
                            } else {
                                stopOfMismatch = readPos;
                            }
                        }

                        if( stopOfMismatch != -1) {
                            numSinceMismatch++;
                        }

                        if( numSinceMismatch > lookAhead || (iii == elementLength - 1 && stopOfMismatch != -1) ) {
                            byte[] refBases = Arrays.copyOfRange( ref, refPosStartOfMismatch, refPosStartOfMismatch + (stopOfMismatch - startOfMismatch) + 1 );
                            byte[] mismatchBases = Arrays.copyOfRange( read, startOfMismatch, stopOfMismatch + 1 );
                            ArrayList<Allele> alleles = new ArrayList<Allele>();
                            alleles.add( Allele.create( refBases, true ) );
                            alleles.add( Allele.create( mismatchBases, false ) );
                            System.out.println( "> SNP/MNP: " + alleles);
                            vcs.add( new VariantContext("HaplotypeCaller", loc.getContig(), loc.getStart() + refPosStartOfMismatch, loc.getStart() + refPosStartOfMismatch + (stopOfMismatch - startOfMismatch), alleles) );
                            numSinceMismatch = -1;
                            stopOfMismatch = -1;
                            startOfMismatch = -1;
                            refPosStartOfMismatch = -1;
                        }

                        refPos++;
                        readPos++;
                    }
                    break;
                }

                case N:
                case H:
                case P:
                default:
                    throw new ReviewedStingException( "Unsupported cigar operator: " + ce.getOperator() );
            }
        }

        if( vcs.size() == 0 ) {
            System.out.println("> Reference!");
        }

        return vcs;
    }

    private static List<VariantContext> genotype( final List<VariantContext> vcs1, final List<VariantContext> vcs2 ) {
        final ArrayList<VariantContext> vcs = new ArrayList<VariantContext>();

        final Iterator<VariantContext> vcs1Iter = vcs1.iterator();
        final Iterator<VariantContext> vcs2Iter = vcs2.iterator();

        VariantContext vc1Hold = null;
        VariantContext vc2Hold = null;

        do {
            final VariantContext vc1 = ( vc1Hold != null ? vc1Hold : (vcs1Iter.hasNext() ? vcs1Iter.next() : null) );
            final VariantContext vc2 = ( vc2Hold != null ? vc2Hold : (vcs2Iter.hasNext() ? vcs2Iter.next() : null) );

            vc1Hold = null;
            vc2Hold = null;

            if( vc1 == null && vc2 != null ) {
                ArrayList<Allele> alleles = new ArrayList<Allele>();
                alleles.addAll( vc2.getAlleles() );
                Genotype gt = new Genotype( "NA12878", alleles );
                HashMap<String,Genotype> genotypeMap = new HashMap<String,Genotype>();
                genotypeMap.put("NA12878", gt);
                vcs.add( VariantContext.modifyGenotypes( vc2, genotypeMap ) );
            } else if( vc1 != null && vc2 == null ) {
                ArrayList<Allele> alleles = new ArrayList<Allele>();
                alleles.addAll( vc1.getAlleles() );
                Genotype gt = new Genotype( "NA12878", alleles );
                HashMap<String,Genotype> genotypeMap = new HashMap<String,Genotype>();
                genotypeMap.put("NA12878", gt);
                vcs.add( VariantContext.modifyGenotypes( vc1, genotypeMap ) );
            } else if( vc1 != null ) { // && vc2 != null
                if( vc1.getStart() == vc2.getStart() ) {
                    ArrayList<Allele> alleles = new ArrayList<Allele>();
                    alleles.add( vc1.getAlternateAllele(0) );
                    alleles.add( vc2.getAlternateAllele(0) );
                    if( alleles.get(0).equals(alleles.get(1)) ) { // check if alt allese match
                        Genotype gt = new Genotype( "NA12878", alleles );
                        HashMap<String,Genotype> genotypeMap = new HashMap<String,Genotype>();
                        genotypeMap.put("NA12878", gt);
                        vcs.add( VariantContext.modifyGenotypes( vc1, genotypeMap ) );
                    } else { // two alt alleles don't match, and don't call multialleleic records yet
                        vc2Hold = vc2;
                        ArrayList<Allele> theseAlleles = new ArrayList<Allele>();
                        theseAlleles.addAll( vc1.getAlleles() );
                        Genotype gt = new Genotype( "NA12878", theseAlleles );
                        HashMap<String,Genotype> genotypeMap = new HashMap<String,Genotype>();
                        genotypeMap.put("NA12878", gt);
                        vcs.add( VariantContext.modifyGenotypes( vc1, genotypeMap ) );
                    }
                } else if( vc1.getStart() < vc2.getStart()) {
                    vc2Hold = vc2;
                    ArrayList<Allele> alleles = new ArrayList<Allele>();
                    alleles.addAll( vc1.getAlleles() );
                    Genotype gt = new Genotype( "NA12878", alleles );
                    HashMap<String,Genotype> genotypeMap = new HashMap<String,Genotype>();
                    genotypeMap.put("NA12878", gt);
                    vcs.add( VariantContext.modifyGenotypes( vc1, genotypeMap ) );
                } else {
                    vc1Hold = vc1;
                    ArrayList<Allele> alleles = new ArrayList<Allele>();
                    alleles.addAll( vc2.getAlleles() );
                    Genotype gt = new Genotype( "NA12878", alleles );
                    HashMap<String,Genotype> genotypeMap = new HashMap<String,Genotype>();
                    genotypeMap.put("NA12878", gt);
                    vcs.add( VariantContext.modifyGenotypes( vc2, genotypeMap ) );
                }
            }


        } while ( vcs1Iter.hasNext() || vcs2Iter.hasNext() || vc1Hold != null || vc2Hold != null );

        return vcs;
    }

    public void alignAllHaplotypes( final List<Haplotype> haplotypes, final byte[] ref, final GenomeLoc loc, final StingSAMFileWriter writer, final SAMRecord exampleRead ) {

        int iii = 0;
        for( final Haplotype h : haplotypes ) {
            final SWPairwiseAlignment swConsensus = new SWPairwiseAlignment( ref, h.bases, SW_MATCH, SW_MISMATCH, SW_GAP, SW_GAP_EXTEND );
            exampleRead.setReadName("Haplotype" + iii);
            exampleRead.setReadBases(h.bases);
            exampleRead.setAlignmentStart(loc.getStart() + swConsensus.getAlignmentStart2wrt1());
            exampleRead.setCigar(swConsensus.getCigar());
            final byte[] quals = new byte[h.bases.length];
            Arrays.fill(quals, (byte) 25);
            exampleRead.setBaseQualities(quals);
            writer.addAlignment(exampleRead);
            iii++;
        }
                
    }
/*
    public void alignAllReads( final Pair<Haplotype,Haplotype> bestTwoHaplotypes, final byte[] ref, final GenomeLoc loc, final StingSAMFileWriter writer, final List<SAMRecord> reads, final double[][] likelihoods ) {

        int iii = 0;
        for( final SAMRecord read : reads ) {
            final Haplotype h = ( likelihoods[iii][0] > likelihoods[iii][1] ? bestTwoHaplotypes.first : bestTwoHaplotypes.second );
            final SWPairwiseAlignment swConsensus = new SWPairwiseAlignment( ref, h.bases, SW_MATCH, SW_MISMATCH, SW_GAP, SW_GAP_EXTEND );



            writer.addAlignment(read);
            iii++;
        }
    }



    // private classes copied from IndelRealigner
    private class AlignedRead {
        private final SAMRecord read;
        private byte[] readBases = null;
        private byte[] baseQuals = null;
        private Cigar newCigar = null;
        private int newStart = -1;
        private int mismatchScoreToReference = 0;
        private long alignerMismatchScore = 0;

        public AlignedRead(SAMRecord read) {
            this.read = read;
            mismatchScoreToReference = 0;
        }

        public SAMRecord getRead() {
               return read;
        }

        public int getReadLength() {
            return readBases != null ? readBases.length : read.getReadLength();
        }

        public byte[] getReadBases() {
            if ( readBases == null )
                getUnclippedBases();
            return readBases;
        }

        public byte[] getBaseQualities() {
            if ( baseQuals == null )
                getUnclippedBases();
            return baseQuals;
        }

        // pull out the bases that aren't clipped out
        private void getUnclippedBases() {
            readBases = new byte[getReadLength()];
            baseQuals = new byte[getReadLength()];
            byte[] actualReadBases = read.getReadBases();
            byte[] actualBaseQuals = read.getBaseQualities();
            int fromIndex = 0, toIndex = 0;

            for ( CigarElement ce : read.getCigar().getCigarElements() ) {
                int elementLength = ce.getLength();
                switch ( ce.getOperator() ) {
                    case S:
                        fromIndex += elementLength;
                        break;
                    case M:
                    case I:
                        System.arraycopy(actualReadBases, fromIndex, readBases, toIndex, elementLength);
                        System.arraycopy(actualBaseQuals, fromIndex, baseQuals, toIndex, elementLength);
                        fromIndex += elementLength;
                        toIndex += elementLength;
                    default:
                        break;
                }
            }

            // if we got clipped, trim the array
            if ( fromIndex != toIndex ) {
                byte[] trimmedRB = new byte[toIndex];
                byte[] trimmedBQ = new byte[toIndex];
                System.arraycopy(readBases, 0, trimmedRB, 0, toIndex);
                System.arraycopy(baseQuals, 0, trimmedBQ, 0, toIndex);
                readBases = trimmedRB;
                baseQuals = trimmedBQ;
            }
        }

        public Cigar getCigar() {
            return (newCigar != null ? newCigar : read.getCigar());
        }

        public void setCigar(Cigar cigar) {
            setCigar(cigar, true);
        }

        // tentatively sets the new Cigar, but it needs to be confirmed later
        public void setCigar(Cigar cigar, boolean fixClippedCigar) {
            if ( cigar == null ) {
                newCigar = null;
                return;
            }

            if ( fixClippedCigar && getReadBases().length < read.getReadLength() )
                cigar = reclipCigar(cigar);

            // no change?
            if ( read.getCigar().equals(cigar) ) {
                newCigar = null;
                return;
            }

            // no indel?
            String str = cigar.toString();
            if ( !str.contains("D") && !str.contains("I") ) {
                logger.debug("Modifying a read with no associated indel; although this is possible, it is highly unlikely.  Perhaps this region should be double-checked: " + read.getReadName() + " near " + read.getReferenceName() + ":" + read.getAlignmentStart());
                //    newCigar = null;
                //    return;
            }

            newCigar = cigar;
        }

        // pull out the bases that aren't clipped out
        private Cigar reclipCigar(Cigar cigar) {
            return IndelRealigner.reclipCigar(cigar, read);
        }

        // tentatively sets the new start, but it needs to be confirmed later
        public void setAlignmentStart(int start) {
            newStart = start;
        }

        public int getAlignmentStart() {
            return (newStart != -1 ? newStart : read.getAlignmentStart());
        }

        public int getOriginalAlignmentStart() {
            return read.getAlignmentStart();
        }

        // finalizes the changes made.
        // returns true if this record actually changes, false otherwise
        public boolean finalizeUpdate() {
            // if we haven't made any changes, don't do anything
            if ( newCigar == null )
                return false;
            if ( newStart == -1 )
                newStart = read.getAlignmentStart();
            else if ( Math.abs(newStart - read.getAlignmentStart()) > MAX_POS_MOVE_ALLOWED ) {
                logger.debug(String.format("Attempting to realign read %s at %d more than %d bases to %d.", read.getReadName(), read.getAlignmentStart(), MAX_POS_MOVE_ALLOWED, newStart));
                return false;
            }

            // annotate the record with the original cigar (and optionally the alignment start)
            if ( !NO_ORIGINAL_ALIGNMENT_TAGS ) {
                read.setAttribute(ORIGINAL_CIGAR_TAG, read.getCigar().toString());
                if ( newStart != read.getAlignmentStart() )
                    read.setAttribute(ORIGINAL_POSITION_TAG, read.getAlignmentStart());
            }

            read.setCigar(newCigar);
            read.setAlignmentStart(newStart);

            return true;
        }

        public void setMismatchScoreToReference(int score) {
            mismatchScoreToReference = score;
        }

        public int getMismatchScoreToReference() {
            return mismatchScoreToReference;
        }

        public void setAlignerMismatchScore(long score) {
            alignerMismatchScore = score;
        }

        public long getAlignerMismatchScore() {
            return alignerMismatchScore;
        }
    }

    private static class Consensus {
        public final byte[] str;
        public final ArrayList<Pair<Integer, Integer>> readIndexes;
        public final int positionOnReference;
        public int mismatchSum;
        public Cigar cigar;

        public Consensus(byte[] str, Cigar cigar, int positionOnReference) {
            this.str = str;
            this.cigar = cigar;
            this.positionOnReference = positionOnReference;
            mismatchSum = 0;
            readIndexes = new ArrayList<Pair<Integer, Integer>>();
        }

        @Override
        public boolean equals(Object o) {
            return ( this == o || (o instanceof Consensus && Arrays.equals(this.str,(((Consensus)o).str)) ) );
        }

        public boolean equals(Consensus c) {
            return ( this == c || Arrays.equals(this.str,c.str) ) ;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(this.str);
        }
    }
*/

}