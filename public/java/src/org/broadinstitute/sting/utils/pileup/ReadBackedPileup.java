/*
 * Copyright (c) 2010, The Broad Institute
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
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.utils.pileup;

import net.sf.samtools.SAMRecord;
import org.broadinstitute.sting.gatk.datasources.sample.Sample;
import org.broadinstitute.sting.utils.GenomeLoc;
import org.broadinstitute.sting.utils.HasGenomeLocation;

import java.util.Collection;
import java.util.List;

/**
 * A data retrieval interface for accessing parts of the pileup.
 *
 * @author mhanna
 * @version 0.1
 */
public interface ReadBackedPileup extends Iterable<PileupElement>, HasGenomeLocation {
    /**
     * Returns a new ReadBackedPileup that is free of deletion spanning reads in this pileup.  Note that this
     * does not copy the data, so both ReadBackedPileups should not be changed.  Doesn't make an unnecessary copy
     * of the pileup (just returns this) if there are no deletions in the pileup.
     *
     * @return
     */
    public ReadBackedPileup getPileupWithoutDeletions();

    /**
     * Returns a new ReadBackedPileup where only one read from an overlapping read
     * pair is retained.  If the two reads in question disagree to their basecall,
     * neither read is retained.  If they agree on the base, the read with the higher
     * quality observation is retained
     *
     * @return the newly filtered pileup
     */
    public ReadBackedPileup getOverlappingFragmentFilteredPileup();

    /**
     * Returns a new ReadBackedPileup that is free of mapping quality zero reads in this pileup.  Note that this
     * does not copy the data, so both ReadBackedPileups should not be changed.  Doesn't make an unnecessary copy
     * of the pileup (just returns this) if there are no MQ0 reads in the pileup.
     *
     * @return
     */
    public ReadBackedPileup getPileupWithoutMappingQualityZeroReads();

    /**
     * Gets the pileup consisting of only reads on the positive strand.
     * @return A read-backed pileup consisting only of reads on the positive strand.
     */
    public ReadBackedPileup getPositiveStrandPileup();

    /**
     * Gets the pileup consisting of only reads on the negative strand.
     * @return A read-backed pileup consisting only of reads on the negative strand.
     */
    public ReadBackedPileup getNegativeStrandPileup();

    /**
     * Gets a pileup consisting of all those elements passed by a given filter.
     * @param filter Filter to use when testing for elements.
     * @return a pileup without the given filtered elements.
     */
    public ReadBackedPileup getFilteredPileup(PileupElementFilter filter);

    /** Returns subset of this pileup that contains only bases with quality >= minBaseQ, coming from
     * reads with mapping qualities >= minMapQ. This method allocates and returns a new instance of ReadBackedPileup.
     * @param minBaseQ
     * @param minMapQ
     * @return
     */
    public ReadBackedPileup getBaseAndMappingFilteredPileup( int minBaseQ, int minMapQ );

    /** Returns subset of this pileup that contains only bases with quality >= minBaseQ.
     * This method allocates and returns a new instance of ReadBackedPileup.
     * @param minBaseQ
     * @return
     */
    public ReadBackedPileup getBaseFilteredPileup( int minBaseQ );

    /** Returns subset of this pileup that contains only bases coming from reads with mapping quality >= minMapQ.
     * This method allocates and returns a new instance of ReadBackedPileup.
     * @param minMapQ
     * @return
     */
    public ReadBackedPileup getMappingFilteredPileup( int minMapQ );

    /**
     * Returns a pileup randomly downsampled to the desiredCoverage.
     *
     * @param desiredCoverage
     * @return
     */
    public ReadBackedPileup getDownsampledPileup(int desiredCoverage);

    /**
     * Gets a collection of all the read groups represented in this pileup.
     * @return A collection of all the read group ids represented in this pileup.
     */
    public Collection<String> getReadGroups();

    /**
     * Gets all the reads associated with a given read group.
     * @param readGroupId Identifier for the read group.
     * @return A pileup containing only the reads in the given read group.
     */
    public ReadBackedPileup getPileupForReadGroup(String readGroupId);

    /**
     * Gets all reads in a given lane id. (Lane ID is the read group
     * id stripped of the last .XX sample identifier added by the GATK).
     * @param laneID The read group ID without the sample identifier added by the GATK.
     * @return A pileup containing the reads from all samples in the given lane.
     */
    public ReadBackedPileup getPileupForLane(String laneID);


     /**
     * Gets a collection of all the samples stored in this pileup.
     * @return Collection of samples in this pileup.
     */
    public Collection<Sample> getSamples();

    /**
     * Gets a collection of *names* of all the samples stored in this pileup.
     * @return Collection of names
     */
    public Collection<String> getSampleNames();


    /**
     * Gets the particular subset of this pileup for all the given sample names.
     * @param sampleNames Name of the sample to use.
     * @return A subset of this pileup containing only reads with the given sample.
     */
    public ReadBackedPileup getPileupForSampleNames(Collection<String> sampleNames);


    /**
     * Gets the particular subset of this pileup with the given sample name.
     * @param sampleName Name of the sample to use.
     * @return A subset of this pileup containing only reads with the given sample.
     */
    public ReadBackedPileup getPileupForSampleName(String sampleName);

    /**
     * Gets the particular subset of this pileup with the given sample.
     * @param sample Sample to use.
     * @return A subset of this pileup containing only reads with the given sample.
     */
    public ReadBackedPileup getPileupForSample(Sample sample);
    
    /**
     * Simple useful routine to count the number of deletion bases in this pileup
     *
     * @return
     */
    public int getNumberOfDeletions();

    public int getNumberOfMappingQualityZeroReads();

    /**
     * @return the number of elements in this pileup
     */
    public int size();

    /**
     * @return true if there are 0 elements in the pileup, false otherwise
     */
    public boolean isEmpty();

    /**
     * @return the location of this pileup
     */
    public GenomeLoc getLocation();

    /**
     * Get counts of A, C, G, T in order, which returns a int[4] vector with counts according
     * to BaseUtils.simpleBaseToBaseIndex for each base.
     *
     * @return
     */
    public int[] getBaseCounts();

    public String getPileupString(Character ref);

    /**
     * Returns a list of the reads in this pileup. Note this call costs O(n) and allocates fresh lists each time
     * @return
     */
    public List<SAMRecord> getReads();

    /**
     * Returns a list of the offsets in this pileup. Note this call costs O(n) and allocates fresh lists each time
     * @return
     */
    public List<Integer> getOffsets();

    /**
     * Returns an array of the bases in this pileup. Note this call costs O(n) and allocates fresh array each time
     * @return
     */
    public byte[] getBases();

    /**
    * Returns an array of the quals in this pileup. Note this call costs O(n) and allocates fresh array each time
    * @return
    */
    public byte[] getQuals();

    /**
     * Get an array of the mapping qualities
     * @return
     */
    public byte[] getMappingQuals();

}
