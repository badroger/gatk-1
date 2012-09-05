package org.broadinstitute.sting.gatk;

import net.sf.samtools.SAMFileHeader;
import net.sf.samtools.SAMFileReader;
import org.broadinstitute.sting.gatk.arguments.ValidationExclusion;
import org.broadinstitute.sting.gatk.datasources.reads.SAMReaderID;
import org.broadinstitute.sting.gatk.filters.ReadFilter;
import org.broadinstitute.sting.gatk.iterators.ReadTransformer;

import java.util.Collection;
import java.util.List;
/**
 * User: hanna
 * Date: May 14, 2009
 * Time: 4:06:26 PM
 * BROAD INSTITUTE SOFTWARE COPYRIGHT NOTICE AND AGREEMENT
 * Software and documentation are copyright 2005 by the Broad Institute.
 * All rights are reserved.
 *
 * Users acknowledge that this software is supplied without any warranty or support.
 * The Broad Institute is not responsible for its use, misuse, or
 * functionality.
 */

/**
 * A data structure containing information about the reads data sources as well as
 * information about how they should be downsampled, sorted, and filtered.
 */
public class ReadProperties {
    private final Collection<SAMReaderID> readers;
    private final SAMFileHeader header;
    private final SAMFileReader.ValidationStringency validationStringency;
    private final DownsamplingMethod downsamplingMethod;
    private final ValidationExclusion exclusionList;
    private final Collection<ReadFilter> supplementalFilters;
    private final List<ReadTransformer> readTransformers;
    private final boolean includeReadsWithDeletionAtLoci;
    private final boolean useOriginalBaseQualities;
    private final byte defaultBaseQualities;

    /**
     * Return true if the walker wants to see reads that contain deletions when looking at locus pileups
     * 
     * @return
     */
    public boolean includeReadsWithDeletionAtLoci() {
        return includeReadsWithDeletionAtLoci;
    }

    /**
     * Gets a list of the files acting as sources of reads.
     * @return A list of files storing reads data.
     */
    public Collection<SAMReaderID> getSAMReaderIDs() {
        return readers;
    }

    /**
     * Gets the sam file header
     * @return the sam file header
     */
    public SAMFileHeader getHeader() {
        return header;
    }

    /**
     * How strict should validation be?
     * @return Stringency of validation.
     */
    public SAMFileReader.ValidationStringency getValidationStringency() {
        return validationStringency;
    }

    /**
     * Gets the method and parameters used when downsampling reads.
     * @return Downsample fraction.
     */
    public DownsamplingMethod getDownsamplingMethod() {
        return downsamplingMethod;
    }

    /**
     * Return whether to 'verify' the reads as we pass through them.
     * @return Whether to verify the reads.
     */
    public ValidationExclusion getValidationExclusionList() {
        return exclusionList;
    }

    public Collection<ReadFilter> getSupplementalFilters() {
        return supplementalFilters;
    }


    public List<ReadTransformer> getReadTransformers() {
        return readTransformers;
    }

    /**
     * Return whether to use original base qualities.
     * @return Whether to use original base qualities.
     */
    public boolean useOriginalBaseQualities() {
        return useOriginalBaseQualities;
    }

    /**
     * @return Default base quality value to fill reads missing base quality information.
     */
    public byte defaultBaseQualities() {
        return defaultBaseQualities;
    }

    /**
     * Extract the command-line arguments having to do with reads input
     * files and store them in an easy-to-work-with package.  Constructor
     * is package protected.
     * @param samFiles list of reads files.
     * @param header sam file header.
     * @param useOriginalBaseQualities True if original base qualities should be used.
     * @param strictness Stringency of reads file parsing.
     * @param downsamplingMethod Method for downsampling reads at a given locus.
     * @param exclusionList what safety checks we're willing to let slide
     * @param supplementalFilters additional filters to dynamically apply.
     * @param includeReadsWithDeletionAtLoci if 'true', the base pileups sent to the walker's map() method
     *         will explicitly list reads with deletion over the current reference base; otherwise, only observed
     *        bases will be seen in the pileups, and the deletions will be skipped silently.
     * @param defaultBaseQualities if the reads have incomplete quality scores, set them all to defaultBaseQuality.
     */
    public ReadProperties( Collection<SAMReaderID> samFiles,
           SAMFileHeader header,
           boolean useOriginalBaseQualities,
           SAMFileReader.ValidationStringency strictness,
           DownsamplingMethod downsamplingMethod,
           ValidationExclusion exclusionList,
           Collection<ReadFilter> supplementalFilters,
           List<ReadTransformer> readTransformers,
           boolean includeReadsWithDeletionAtLoci,
           byte defaultBaseQualities) {
        this.readers = samFiles;
        this.header = header;
        this.validationStringency = strictness;
        this.downsamplingMethod = downsamplingMethod == null ? DownsamplingMethod.NONE : downsamplingMethod;
        this.exclusionList = exclusionList == null ? new ValidationExclusion() : exclusionList;
        this.supplementalFilters = supplementalFilters;
        this.readTransformers = readTransformers;
        this.includeReadsWithDeletionAtLoci = includeReadsWithDeletionAtLoci;
        this.useOriginalBaseQualities = useOriginalBaseQualities;
        this.defaultBaseQualities = defaultBaseQualities;
    }
}
