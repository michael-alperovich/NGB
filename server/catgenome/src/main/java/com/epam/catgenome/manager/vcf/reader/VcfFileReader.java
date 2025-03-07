/*
 * MIT License
 *
 * Copyright (c) 2016-2021 EPAM Systems
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.epam.catgenome.manager.vcf.reader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.epam.catgenome.util.feature.reader.AbstractEnhancedFeatureReader;
import com.epam.catgenome.util.feature.reader.EhCacheBasedIndexCache;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.util.Assert;

import com.epam.catgenome.component.MessageCode;
import com.epam.catgenome.component.MessageHelper;
import com.epam.catgenome.constant.Constants;
import com.epam.catgenome.constant.MessagesConstants;
import com.epam.catgenome.entity.reference.Chromosome;
import com.epam.catgenome.entity.track.Track;
import com.epam.catgenome.entity.vcf.Filter;
import com.epam.catgenome.entity.vcf.GenotypeData;
import com.epam.catgenome.entity.vcf.OrganismType;
import com.epam.catgenome.entity.vcf.Variation;
import com.epam.catgenome.entity.vcf.VariationType;
import com.epam.catgenome.entity.vcf.VcfFile;
import com.epam.catgenome.exception.VcfReadingException;
import com.epam.catgenome.manager.FileManager;
import com.epam.catgenome.manager.reference.ReferenceGenomeManager;
import com.epam.catgenome.util.Utils;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.tribble.FeatureReader;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFCodec;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLine;
import htsjdk.variant.vcf.VCFInfoHeaderLine;

/**
 * Source:      VcfFileReader
 * Created:     14.10.16, 16:59
 * Project:     CATGenome Browser
 * Make:        IntelliJ IDEA 14.1.4, JDK 1.8
 * {@code VcfFileReader} is an implementation of {@code VcfReader} for reading variations data from
 * VCF files
 */
@Slf4j
public class VcfFileReader extends AbstractVcfReader {
    private FileManager fileManager;

    public static final double HTSJDK_WRONG_QUALITY = -10.0;
    public static final String NO_STRAIN_GENOTYPE_STRING = ".";

    protected static final String BIND_CIPOS_ATTRIBUTE = "CIPOS";

    /**
     * Creates a {@code VcfFileReader} instance
     * @param fileManager for file access
     * @param referenceGenomeManager for getting reference data
     */
    public VcfFileReader(final FileManager fileManager, final ReferenceGenomeManager referenceGenomeManager) {
        this.fileManager = fileManager;
        this.referenceGenomeManager = referenceGenomeManager;
    }

    /**
     * Reads the variations data from the VCF file and loads it into a track
     * @param vcfFile data source
     * @param track for loading data
     * @param chromosome reference sequence
     * @param sampleIndex determines fro which sample from the file variations are loaded
     * @param loadInfo if true data from the INFO fields from VCF file will be loaded into the track,
     *                 otherwise it will be ignored
     * @return {@code Track} filled with variations from a specified {@code VcfFile}
     * @throws VcfReadingException
     */
    @Override
    public Track<Variation> readVariations(final VcfFile vcfFile, final Track<Variation> track,
                                           final Chromosome chromosome, final Integer sampleIndex,
                                           final boolean loadInfo, final boolean collapse,
                                           final EhCacheBasedIndexCache indexCache) throws VcfReadingException {
        try (FeatureReader<VariantContext> reader = AbstractEnhancedFeatureReader.getFeatureReader(vcfFile.getPath(),
                vcfFile.getIndex().getPath(), new VCFCodec(), true, indexCache)) {
            if (checkBounds(vcfFile, track, chromosome, loadInfo)) {
                return track;
            }
            try (CloseableIterator<VariantContext> iterator = Utils.query(reader, chromosome.getName(), track
                    .getStartIndex(), track.getEndIndex())) {
                VCFHeader header = (VCFHeader) reader.getHeader();
                track.setBlocks(doReadVariations(iterator, track, header, vcfFile, sampleIndex, loadInfo, collapse));
            }
        } catch (IOException e) {
            throw new VcfReadingException(vcfFile, e);
        }
        return track;
    }

    @Override
    public Variation getNextOrPreviousVariation(final int fromPosition, final VcfFile vcfFile,
                                                final Integer sampleIndex, final Chromosome chromosome, boolean forward,
                                                final EhCacheBasedIndexCache indexCache) throws VcfReadingException {
        final int end = forward ? chromosome.getSize() : 0;
        if (isOutOfBounds(fromPosition, forward, end)) { // no next features
            return null;
        }
        try (FeatureReader<VariantContext> reader = AbstractEnhancedFeatureReader.getFeatureReader(vcfFile.getPath(),
                vcfFile.getIndex().getPath(), new VCFCodec(), true, indexCache)) {
            return readNextOrPreviousVariation(fromPosition, vcfFile, sampleIndex, chromosome,
                    forward, end, reader);
        } catch (IOException e) {
            throw new VcfReadingException(vcfFile, e);
        }
    }

    /**
     * Translates HTSJDK's {@code VariantContext} object into our {@code Variant} entity
     *
     * @param context     a {@code VariantContext} object, that presents a variation from parsed VCF file.
     * @param header      a {@code VCFHeader} object, that represents a header of parsed VCF file.
     * @param sampleIndex {@code Integer} a name of a sample.
     * @return a {@code Variation} object, representing desired variation.
     */
    public static Variation createVariation(final VariantContext context, final VCFHeader header,
                                            final Integer sampleIndex) {
        final String ref = context.getReference().getDisplayString();
        final List<String> alt = context.getAlternateAlleles().stream().map(Allele::getDisplayString)
                .collect(Collectors.toList());

        // First, determine OrganismType
        final Map<String, GenotypeData> genotypeData = getGenotypeData(context);

        final Variation variation = new Variation(context.getStart(), context.getEnd(), ref, alt);
        variation.setGenotypeData(genotypeData);

        variation.setFailedFilters(context.getFilters().stream().map(f -> {
            VCFHeaderLine vcfHeaderLine = header.getFilterHeaderLine(f);
            return new Filter(f, vcfHeaderLine != null ? vcfHeaderLine.getValue() : null);
        }).collect(Collectors.toList()));

        variation.setIdentifier(context.getID());

        final Double qual = context.getPhredScaledQual();
        variation.setQuality(Double.compare(qual, HTSJDK_WRONG_QUALITY) != 0 ? qual : 0);

        determineVariationType(context, sampleIndex, variation);

        return variation;
    }

    public static boolean isEmptyStrain(final Genotype genotype) {
        return genotype.getGenotypeString().equals(NO_STRAIN_GENOTYPE_STRING);
    }

    public static boolean isVariation(final Variation variation) {
        return variation.getGenotypeData() == null || variation.getGenotypeData().isEmpty() ||
                variation.getGenotypeData().values().stream()
                        .anyMatch(g -> !g.getOrganismType().equals(OrganismType.NO_VARIATION));
    }

    @NotNull
    private static Map<String, GenotypeData> getGenotypeData(final VariantContext context) {
        final Map<String, GenotypeData> genotypeDataMap = new LinkedHashMap<>();
        for (Genotype genotype: context.getGenotypes()) {
            if (!isEmptyStrain(genotype)) {
                GenotypeData genotypeData;
                int[] genotypeArray = null;
                OrganismType organismType;
                switch (genotype.getType()) {
                    case HOM_VAR:
                        organismType = OrganismType.HOMOZYGOUS;
                        int altIndex = context.getAlternateAlleles().indexOf(genotype.getAllele(0)) + 1;
                        genotypeArray = new int[]{altIndex, altIndex};
                        break;
                    case MIXED:
                    case HET:
                        genotypeArray = new int[2];
                        organismType = determineHeterozygousGenotype(context, genotype, genotypeArray);
                        break;
                    case UNAVAILABLE:
                    case NO_CALL:
                        organismType = OrganismType.NOT_SPECIFIED;
                        break;
                    case HOM_REF:
                        organismType = OrganismType.NO_VARIATION;
                        genotypeArray = new int[]{0, 0};
                        break;
                    default:
                        organismType = OrganismType.NOT_SPECIFIED;
                }
                genotypeData = new GenotypeData(organismType, genotypeArray, genotype.getGenotypeString());
                genotypeData.setExtendedAttributes(genotype.getExtendedAttributes());
                genotypeDataMap.put(genotype.getSampleName(), genotypeData);
            }
        }
        return genotypeDataMap;
    }

    private boolean isOutOfBounds(final int fromPosition, final boolean forward, final int end) {
        return (forward && fromPosition + 1 >= end) || (!forward && fromPosition - 1 <= end);
    }

    private Variation readNextOrPreviousVariation(final int fromPosition, final VcfFile vcfFile,
            final Integer sampleIndex, final Chromosome chromosome, final boolean forward, final int end,
            final FeatureReader<VariantContext> reader) throws IOException {
        int bound = end;
        if (vcfFile.getCompressed()) {
            bound = getEndWithBounds(vcfFile, chromosome, forward);
        }
        final VCFHeader vcfHeader = (VCFHeader) reader.getHeader();
        return forward ? getNextVariation(fromPosition, sampleIndex, chromosome, bound,
                reader, vcfHeader) : getPreviousVariation(fromPosition, sampleIndex, chromosome, bound,
                reader, vcfHeader);
    }

    private int getEndWithBounds(final VcfFile vcfFile, final Chromosome chromosome, final boolean forward)
            throws IOException {
        final Map<String, Pair<Integer, Integer>> metaMap = fileManager.loadIndexMetadata(vcfFile);
        Pair<Integer, Integer> bounds = metaMap.get(chromosome.getName());
        if (bounds == null) {
            bounds = metaMap.get(Utils.changeChromosomeName(chromosome.getName()));
        }
        Assert.notNull(bounds, MessageHelper.getMessage(MessageCode.NO_SUCH_CHROMOSOME));
        return forward ? bounds.getRight() : bounds.getLeft();
    }

    @Nullable
    private Variation getPreviousVariation(final int fromPosition, final Integer sampleIndex,
                                           final Chromosome chromosome, final int end,
                                           final FeatureReader<VariantContext> reader,
                                           final VCFHeader vcfHeader) throws IOException {
        Variation lastFeature = null;
        int i = 0;
        boolean lastChunk = false;
        while (lastFeature == null) {
            if (lastChunk) {
                break;
            }
            int firstIndex = fromPosition - Constants.PREV_FEATURE_OFFSET * (i + 1);
            int lastIndex = fromPosition - 1 - Constants.PREV_FEATURE_OFFSET * i;
            if (firstIndex < end) {
                firstIndex = end;
                lastChunk = true; // this is the last chunk to be traversed
            }

            try (CloseableIterator<VariantContext> iterator = Utils.query(reader, chromosome.getName(),
                    firstIndex, lastIndex)) {
                // instead traversing the whole file, read it by small chunks, 10000 bps
                // long. Hopefully, the desired feature will be in first/second chunk
                lastFeature = createVariations(sampleIndex, vcfHeader, iterator, fromPosition);
                i++;
            }
        }
        return lastFeature;
    }

    private Variation createVariations(final Integer sampleIndex, final VCFHeader vcfHeader,
                                       final CloseableIterator<VariantContext> iterator, final int fromPosition) {
        Variation lastFeature = null;
        while (iterator.hasNext()) {
            Variation variation = createVariation(iterator.next(), vcfHeader, sampleIndex);
            if (isVariation(variation) &&
                    variation.getEndIndex() < fromPosition) {
                lastFeature = variation;
            }
        }
        return lastFeature;
    }

    @Nullable
    private Variation getNextVariation(final int fromPosition, final Integer sampleIndex, final Chromosome chromosome,
                                       final int end, final FeatureReader<VariantContext> reader,
                                       final VCFHeader vcfHeader) throws IOException {
        try (CloseableIterator<VariantContext> iterator = Utils.query(reader, chromosome.getName(),
                fromPosition + 1, end)) {
            while (iterator.hasNext()) {
                VariantContext feature = iterator.next();
                Variation variation = createVariation(feature, vcfHeader, sampleIndex);
                if (isVariation(variation) && variation.getStartIndex() > fromPosition) {
                    return variation;
                }
            }
        }
        return null;
    }

    private boolean checkBounds(final VcfFile vcfFile, final Track<Variation> track, final Chromosome chromosome,
                                final boolean loadInfo) throws IOException {
        // Bounds metadata should be load only for track loading to improve performance
        // Load bounds metadata for this file
        return !loadInfo && vcfFile.getCompressed() && checkBoundsForCompressedFile(vcfFile, track,
                chromosome);
    }

    private boolean checkBoundsForCompressedFile(final VcfFile vcfFile, final Track<Variation> track,
                                                 final Chromosome chromosome) throws IOException {
        final Map<String, Pair<Integer, Integer>> metaMap = fileManager.loadIndexMetadata(vcfFile);
        Pair<Integer, Integer> bounds = metaMap.get(chromosome.getName());
        if (bounds == null) {
            bounds = metaMap.get(Utils.changeChromosomeName(chromosome.getName()));
        }
        if (bounds == null) {
            track.setBlocks(Collections.emptyList());
            return true;
        }
        if (track.getStartIndex() < bounds.getLeft()) {
            track.setStartIndex(bounds.getLeft());
        }
        if (track.getEndIndex() > bounds.getRight()) {
            track.setEndIndex(bounds.getRight());
        }
        // If we are out of variation bounds, return empty list of variations
        if (track.getStartIndex() > bounds.getRight() || track.getEndIndex() < bounds.getLeft()) {
            track.setBlocks(Collections.emptyList());
            return true;
        }
        return false;
    }

    private List<Variation> doReadVariations(final CloseableIterator<VariantContext> iterator,
                                             final Track<Variation> track, final VCFHeader header,
                                             final VcfFile vcfFile, final Integer sampleIndex, final boolean loadInfo,
                                             final boolean collapse) throws IOException {
        if (track.getScaleFactor() >= 1 || !collapse) {
            ArrayList<Variation> variations = new ArrayList<>();
            while (iterator.hasNext()) {
                VariantContext context = iterator.next();
                Variation variation = createVariation(context, header, sampleIndex);
                if (loadInfo) {
                    parseInfo(variation, context, header, sampleIndex, vcfFile);
                }
                if (isVariation(variation)) {
                    variations.add(variation);
                }
            }
            return variations;
        } else {
            return loadStatisticVariations(iterator, track, header, vcfFile, sampleIndex, loadInfo);
        }
    }

    private List<Variation> loadStatisticVariations(final CloseableIterator<VariantContext> iterator,
                                                    final Track<Variation> track, final VCFHeader header,
                                                    final VcfFile vcfFile, final Integer sampleIndex,
                                                    final boolean loadInfo) {
        final ArrayList<Variation> variations = new ArrayList<>();
        final int step = (int) Math.ceil((double) 1 / track.getScaleFactor());
        final int from = track.getStartIndex();
        int to = from + step;
        boolean found = false;
        int variationCount = 0; // On small scale we need to count overlapping variations
        final List<Variation> extendingVariations = new ArrayList<>(); // variations, that extend one pixel region
        VariantContext lastContext = null;
        while (iterator.hasNext()) {
            VariantContext context = iterator.next();
            Variation variation = createVariation(context, header, sampleIndex);
            if (loadInfo) {
                parseInfo(variation, context, header, sampleIndex, vcfFile);
            }

            if (variation.getType() == VariationType.BND) {
                variations.add(variation);
                continue;
            }

            if (context.getStart() > to) {
                found = false;
                to = to + step < context.getStart() ? (context.getStart() + step) : (to + step);
                if (!variations.isEmpty()) {
                    tryToGroupVariations(variations, variationCount, lastContext);
                }
                variationCount = 0;
                variations.addAll(extendingVariations);
                extendingVariations.clear();
            }

            if (context.getEnd() > to && context.getStart() >= from && context.getType() ==
                    VariantContext.Type.SYMBOLIC) {
                if (isVariation(variation)) {
                    extendingVariations.add(variation);
                }
                continue;
            }

            if (!found && (isVariation(variation))) {
                variations.add(variation);
                found = true;
            }

            if (isVariation(variation)) {
                variationCount++;
            }
            lastContext = context;
        }
        if (!variations.isEmpty()) {
            tryToGroupVariations(variations, variationCount, lastContext);
        }
        variations.addAll(extendingVariations);

        return variations;
    }

    private void tryToGroupVariations(final ArrayList<Variation> variations, final int variationCount,
                                      final VariantContext lastContext) {
        final Variation lastVariation = findLastNotBndVariation(variations);

        if (lastVariation != null && lastContext != null) {
            lastVariation.setVariationsCount(variationCount);
            if (variationCount > 1) { // set overlapping variations type to VariationType.STATISTIC
                lastVariation.setType(VariationType.STATISTIC);
                lastVariation.setEndIndex(lastContext.getEnd());
            }
        }
    }

    /**
     * Translates HTSJDK's ambiguous Heterozygous type into our HETEROZYGOUS or HETERO_VAR organism types
     *
     * @param context       {@code VariantContext} a context, from which genotype is being achieved
     * @param genotype      {@code Genotype} a source genotype
     * @param genotypeArray {@code} an array, in which correct genotype will be stored in format {0, 0}, {0, 1}, etc.
     * @return correct {@code OrganismType}
     */
    private static OrganismType determineHeterozygousGenotype(final VariantContext context, final Genotype genotype,
                                                              final int[] genotypeArray) {
        OrganismType organismType = null;
        for (int i = 0; i < genotype.getAlleles().size(); i++) {
            if (genotype.getAllele(i).isReference()) {
                organismType = OrganismType.HETEROZYGOUS;
                genotypeArray[i] = 0;
            } else {
                if (organismType == null) {
                    organismType = OrganismType.HETERO_VAR;
                }

                genotypeArray[i] = context.getAlternateAlleles().indexOf(genotype.getAllele(i)) + 1;
            }
        }

        return organismType;
    }

    private static void determineVariationType(final VariantContext context, final Integer sampleIndex,
                                               final Variation variation) {
        final VariantContext.Type type = context.getType(); // Determine VariationType
        switch (type) {
            case SNP:
                variation.setType(VariationType.SNV);
                break;
            case INDEL:
            case MIXED:
                variation.setType(determineInDel(context, sampleIndex));
                break;
            case MNP:
                variation.setType(VariationType.MNP);
                break;
            case SYMBOLIC:
                parseSymbolicVariation(variation, context);
                break;
            default:
                variation.setType(null);
                setNoVariationOrganism(variation);
                break;
        }
        if (variation.getType() == null) {
            variation.setType(VariationType.UNK);
        }
    }

    private static void setNoVariationOrganism(final Variation variation) {
        if (variation.getGenotypeData() == null) {
            variation.setGenotypeData(new HashMap<>());
        }
        variation.getGenotypeData().values().forEach(g -> g.setOrganismType(OrganismType.NO_VARIATION));
    }

    /**
     * Translates variation's extended information from {@code VariantContext} to our {@code Variation} object
     *
     * @param variation   a {@code Variation} object, for which extended information should be set
     * @param context     a {@code VariantContext} object, that is a source for this information
     * @param header      a {@code VCFHeader} header of VCF file
     * @param sampleIndex {@code Integer} a sample index from a VCF file. If is null, no genotype information
     * @param vcfFile     a {@code VcfFile}, where variation is located
     */
    private void parseInfo(final Variation variation, final VariantContext context, final VCFHeader header,
                           final Integer sampleIndex, final VcfFile vcfFile) {
        final Map<String, Variation.InfoField> verboseAttributes = new HashMap<>();
        final Map<String, Object> attributes = context.getAttributes();
        for (final Map.Entry<String, Object> e : attributes.entrySet()) {
            final String attributeKey = e.getKey();
            final VCFInfoHeaderLine headerLine = header.getInfoHeaderLine(attributeKey);
            if (headerLine != null) {
                verboseAttributes.put(attributeKey, new Variation.InfoField(headerLine
                        .getDescription(), e.getValue()));
            } else {
                log.warn(MessageHelper.getMessage(MessagesConstants.ERROR_VCF_HEADER, attributeKey));
                verboseAttributes.put(attributeKey, new Variation.InfoField(attributeKey, e.getValue()));
            }
        }
        variation.setInfo(verboseAttributes);
        setGenotypeData(variation, header, context);
        if (variation.getType() == VariationType.BND) {
            parseBNFDInfo(variation, context, sampleIndex, vcfFile);
        }
    }

    private void parseBNFDInfo(final Variation variation, final VariantContext context, final Integer sampleIndex,
                               final VcfFile vcfFile) {
        if (variation.getBindInfo() == null) {
            variation.setBindInfo(new HashMap<>());
        }
        final Allele alt = context.getAlternateAllele(sampleIndex != null ? sampleIndex : 0);

        for (Pattern pattern : BIND_PATTERNS) {
            Matcher matcher = pattern.matcher(alt.getDisplayString());
            if (matcher.matches()) {
                String chrName = matcher.group(1);
                Optional<Chromosome> chromosome = referenceGenomeManager.loadChromosomes(vcfFile.getReferenceId())
                        .stream()
                        .filter(c -> c.getName().equals(chrName) ||
                                c.getName().equals(Utils.changeChromosomeName(chrName)))
                        .findAny();

                variation.getBindInfo().put(BIND_CHR_ATTRIBUTE, chromosome.isPresent() ?
                        chromosome.get().getId() : chrName);
                variation.getBindInfo().put(BIND_POS_ATTRIBUTE, matcher.group(2));
                break;
            }
        }
    }

    private void setGenotypeData(final Variation variation, final VCFHeader header, final VariantContext context) {
        if (variation.getGenotypeData() == null) {
            return;
        }
        for (String sampleName: variation.getGenotypeData().keySet()) {
            Genotype genotype = context.getGenotype(sampleName);
            Map<String, Object> genotypeInfo = new HashMap<>();
            if (genotype.getAD() != null) {
                genotypeInfo.put(header.getFormatHeaderLine("AD").getDescription(), genotype.getAD());
            }
            genotypeInfo.put(header.getFormatHeaderLine("GQ") != null ? header.getFormatHeaderLine("GQ")
                    .getDescription() : "GQ", genotype.getGQ());
            genotypeInfo.put(header.getFormatHeaderLine("DP") != null ? header.getFormatHeaderLine("DP")
                    .getDescription() : "DP", genotype.getDP());
            if (genotype.getPL() != null) {
                genotypeInfo.put(header.getFormatHeaderLine("PL") != null ? header.getFormatHeaderLine("PL")
                        .getDescription() : "PL", genotype.getPL());
            }
            genotypeInfo.put(header.getFormatHeaderLine("GT") != null ? header.getFormatHeaderLine("GT")
                    .getDescription() : "GT", genotype.getGenotypeString());
            variation.getGenotypeData().get(sampleName).setInfo(genotypeInfo);
            variation.getGenotypeData().get(sampleName).setExtendedAttributes(genotype.getExtendedAttributes());
        }
    }

    /**
     * Translates HTSJDK's ambiguous INDEL type into our INS, DEL or MIXED variation types
     *
     * @param context     {@code VariantContext}, from which variation type is being achieved.
     * @param sampleIndex {@code Integer} a sample index from VCF file. If is null, will try to guess VariationType by
     *                    first allele.
     * @return correct {@code VariationType}
     */
    private static VariationType determineInDel(final VariantContext context, final Integer sampleIndex) {
        final Genotype genotype = sampleIndex != null ? context.getGenotype(sampleIndex) : null;

        if (genotype == null || CollectionUtils.isEmpty(genotype.getAlleles())) {
            // No genotype information, trying to guess by first alt allele
            return context.getAlternateAlleles().get(0).length() > context.getReference().length() ?
                    VariationType.INS : VariationType.DEL;
        } else {
            return getVariationTypeFromAlleles(context, genotype);
        }
    }

    @NotNull
    private static VariationType getVariationTypeFromAlleles(final VariantContext context, final Genotype genotype) {
        if (genotype.getAlleles().size() > 1) { // Complex deletion/insertion
            if (genotype.getAllele(0).isReference() ^ genotype.getAllele(1).isReference()) { // if one is reference
                return getVariationTypeForComplexInDels(genotype);
            } else {
                return getVariationTypeForSimpleInDels(context, genotype);
            }
        } else {
            return genotype.getAlleles().get(0).isReference() ? VariationType.DEL : VariationType.INS;
        }
    }

    @NotNull
    private static VariationType getVariationTypeForSimpleInDels(final VariantContext context,
                                                                 final Genotype genotype) {
        final Allele firstAllele = genotype.getAllele(0);
        final Allele secondAllele = genotype.getAllele(1);
        VariationType type;
        if (firstAllele.isNonReference() && secondAllele.isNonReference()) {
            // if both are alt
            if (firstAllele.length() > context.getReference().length()
                    && secondAllele.length() > context.getReference().length()) {
                type = VariationType.INS;
            } else if (firstAllele.length() < context.getReference().length() &&
                    secondAllele.length() < context.getReference().length()) {
                type = VariationType.DEL;
            } else {
                type = VariationType.MIXED;
            }
        } else {
            // if both are ref
            type = VariationType.MIXED;
        }
        return type;
    }

    @NotNull
    private static VariationType getVariationTypeForComplexInDels(final Genotype genotype) {
        final Allele firstAllele = genotype.getAllele(0);
        final Allele secondAllele = genotype.getAllele(1);
        if (firstAllele.length() == secondAllele.length()) { // if it is a change
            return firstAllele.length() == 1 ? VariationType.SNV : VariationType.MNP;
        }
        VariationType type;
        if (firstAllele.isReference()) { // if it is an insertion/deletion
            type = genotype.getAlleles().get(0).length() > genotype.getAlleles().get(1).length() ?
                    VariationType.DEL : VariationType.INS;
        } else if (secondAllele.isReference()) {
            type = genotype.getAlleles().get(1).length() > genotype.getAlleles().get(0).length() ?
                    VariationType.DEL : VariationType.INS;
        } else {
            type = VariationType.MIXED;
        }
        return type;
    }

    private static void parseSymbolicVariation(final Variation variation, final VariantContext context) {
        variation.setStructural(true);
        VariationType type = null;
        for (Allele alt : context.getAlternateAlleles()) {
            if (STRUCT_DEL_TAG.equals(alt.getDisplayString())) {
                type = VariationType.DEL;
                break;
            }
            if (STRUCT_INS_TAG.equals(alt.getDisplayString())) {
                type = VariationType.INS;
                break;
            }
            if (INV_TAG.equals(alt.getDisplayString())) {
                type = VariationType.INV;
                break;
            }
            if (alt.getDisplayString() != null && DUP_TAG.matcher(alt.getDisplayString()).matches()) {
                type = VariationType.DUP;
                break;
            }
            type = getBDNVariationType(variation, context, alt);
            if (type != null) {
                break;
            }
        }
        variation.setType(type);
    }

    private static VariationType getBDNVariationType(final Variation variation, final VariantContext context,
                                                     final Allele alt) {
        VariationType type = null;
        for (Pattern pattern : BIND_PATTERNS) {
            Matcher matcher = pattern.matcher(alt.getDisplayString());
            if (matcher.matches()) {
                type = VariationType.BND;
                if (context.getAttribute(BIND_CIPOS_ATTRIBUTE) != null) {
                    variation.setBindInfo(new HashMap<>());
                    variation.getBindInfo().put(BIND_CIPOS_ATTRIBUTE, context.getAttribute(BIND_CIPOS_ATTRIBUTE));
                }
                return type;
            }
        }
        return type;
    }
}
