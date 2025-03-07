/*
 * MIT License
 *
 * Copyright (c) 2016 EPAM Systems
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

package com.epam.catgenome.manager.reference;

import static com.epam.catgenome.component.MessageHelper.getMessage;

import com.epam.catgenome.dao.reference.SpeciesDao;
import com.epam.catgenome.entity.reference.Species;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import com.epam.catgenome.entity.BaseEntity;
import com.epam.catgenome.entity.BiologicalDataItem;
import com.epam.catgenome.entity.BiologicalDataItemFormat;
import com.epam.catgenome.entity.BiologicalDataItemResourceType;
import com.epam.catgenome.entity.FeatureFile;
import com.epam.catgenome.entity.gene.GeneFile;
import com.epam.catgenome.entity.security.AbstractSecuredEntity;
import com.epam.catgenome.entity.security.AclClass;
import com.epam.catgenome.exception.FeatureIndexException;
import com.epam.catgenome.manager.SecuredEntityManager;
import com.epam.catgenome.manager.metadata.MetadataManager;
import com.epam.catgenome.security.acl.aspect.AclSync;
import com.epam.catgenome.util.ListMapCollector;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import com.epam.catgenome.component.MessageCode;
import com.epam.catgenome.constant.MessagesConstants;
import com.epam.catgenome.dao.BiologicalDataItemDao;
import com.epam.catgenome.dao.gene.GeneFileDao;
import com.epam.catgenome.dao.project.ProjectDao;
import com.epam.catgenome.dao.reference.ReferenceGenomeDao;
import com.epam.catgenome.entity.project.Project;
import com.epam.catgenome.entity.reference.Chromosome;
import com.epam.catgenome.entity.reference.Reference;
import org.springframework.util.StringUtils;

/**
 * {@code ReferenceManager} represents a service class designed to encapsulate all business
 * logic operations required to manage metadata concerned with reference genomes and sets
 * of their chromosomes.
 */
@AclSync
@Service
public class ReferenceGenomeManager implements SecuredEntityManager {

    @Autowired
    private ReferenceGenomeDao referenceGenomeDao;

    @Autowired
    private ProjectDao projectDao;

    @Autowired
    private BiologicalDataItemDao biologicalDataItemDao;

    @Autowired
    private GeneFileDao geneFileDao;

    @Autowired
    private SpeciesDao speciesDao;

    @Autowired
    private MetadataManager metadataManager;

    private static final Set<BiologicalDataItemFormat> ANNOTATION_FORMATS = new HashSet<>();
    static {
        ANNOTATION_FORMATS.add(BiologicalDataItemFormat.BED);
        ANNOTATION_FORMATS.add(BiologicalDataItemFormat.WIG);
        ANNOTATION_FORMATS.add(BiologicalDataItemFormat.VCF);
        ANNOTATION_FORMATS.add(BiologicalDataItemFormat.GENE);
        ANNOTATION_FORMATS.add(BiologicalDataItemFormat.HEATMAP);
    }

    /**
     * Generates and returns ID value, that has to be used to identify each certain reference
     * genome in the system.
     *
     * @return {@code Long}
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Long createReferenceId() {
        return referenceGenomeDao.createReferenceGenomeId();
    }


    /**
     * Saves metadata information about a reference genome that should become available in the
     * system.
     *
     * @param reference {@code Reference} represents a reference genome metadata that should be
     *                  stored in the system.
     * @return {@code Reference} is the same with the passed one to this call, but after succeeded
     * call it provides access to ID values
     * @throws IllegalArgumentException will be thrown if reference ID isn't specified or reference
     *                                  doesn't provide information about related chromosomes
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Reference create(final Reference reference) {
        Assert.isTrue(CollectionUtils.isNotEmpty(reference.getChromosomes()),
                getMessage("error.reference.aborted.saving.chromosomes"));
        Assert.notNull(reference.getId(), getMessage(MessageCode.UNKNOWN_REFERENCE_ID));
        biologicalDataItemDao.createBiologicalDataItem(reference.getIndex());
        if (reference.getCreatedDate() == null) {
            reference.setCreatedDate(new Date());
        }
        if (reference.getType() == null) {
            reference.setType(BiologicalDataItemResourceType.FILE);
        }
        if (reference.getBioDataItemId() == null) {
            final Long referenceId = reference.getId();
            biologicalDataItemDao.createBiologicalDataItem(reference);
            referenceGenomeDao.createReferenceGenome(reference, referenceId);
        } else {
            referenceGenomeDao.createReferenceGenome(reference);
        }
        referenceGenomeDao.saveChromosomes(reference.getId(), reference.getChromosomes());
        return reference;
    }

    /**
     * Deletes a {@code Reference} instance from the server and database
     * @param reference an instnce to delete
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void delete(final Reference reference) {
        Assert.notNull(reference, MessagesConstants.ERROR_INVALID_PARAM);
        Assert.notNull(reference.getId(), MessagesConstants.ERROR_INVALID_PARAM);

        if (reference.getType() != BiologicalDataItemResourceType.GA4GH) {
            List<Project> projectsWhereFileInUse = projectDao.loadProjectsByBioDataItemId(
                    reference.getBioDataItemId());
            Assert.isTrue(projectsWhereFileInUse.isEmpty(), getMessage(MessagesConstants.ERROR_FILE_IN_USE,
                    reference.getName(), reference.getId(), projectsWhereFileInUse.stream().map(BaseEntity::getName)
                            .collect(Collectors.joining(", "))));
            List<BaseEntity> fileList = loadAllFile(reference.getId());
            Assert.isTrue(fileList.isEmpty(), getMessage(MessagesConstants.ERROR_FILE_IN_LINK,
                    reference.getName(), reference.getId(), fileList.stream().map(BaseEntity::getName)
                            .collect(Collectors.joining(", "))));
        }
        referenceGenomeDao.unregisterReferenceGenome(reference.getId());
        biologicalDataItemDao.deleteBiologicalDataItem(reference.getBioDataItemId());
        metadataManager.delete(reference);
    }

    /**
     * Returns {@code Reference} entity associated with the given ID. It also includes all information
     * about all chromosomes associated with a reference genome.
     *
     * @param referenceId {@code Long} specifies ID of a reference genome which metadata should be loaded
     * @return {@code Reference} specifies a reference genome metadata, including the list of chromosome
     * associated with it
     * @throws IllegalArgumentException will be thrown in a case, if no reference with the given ID can be
     *                                  found in the system
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public Reference load(final Long referenceId) {
        final Reference reference = referenceGenomeDao.loadReferenceGenome(referenceId);
        Assert.notNull(reference, getMessage(MessageCode.NO_SUCH_REFERENCE));

        final List<Chromosome> chromosomes = referenceGenomeDao.loadAllChromosomesByReferenceId(referenceId);
        reference.setChromosomes(chromosomes);

        if (reference.getGeneFile() != null) {
            reference.setGeneFile(geneFileDao.loadGeneFile(reference.getGeneFile().getId()));
        }

        reference.setAnnotationFiles(
                getAnnotationFilesByReferenceId(referenceId)
        );
        return reference;
    }

    private List<BiologicalDataItem> getAnnotationFilesByReferenceId(Long referenceId) {
        return biologicalDataItemDao.loadBiologicalDataItemsByIds(
                referenceGenomeDao.loadAnnotationFileIdsByReferenceId(referenceId));
    }

    /**
     * Returns {@code Reference} entity associated with the given BiologicalDatItemID.
     * Chromosomes are not loaded in this method.
     * @param dataItemId {@code Long} specifies BiologicalDataItemID associated with
     *                               a reference genome which metadata should be loaded
     * @return {@code Reference} specifies a reference genome metadata, without the list of chromosome
     * associated with it
     * @throws IllegalArgumentException will be thrown in a case, if no reference with the given ID can be
     *                                  found in the system
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public Reference loadReferenceGenomeByBioItemId(final Long dataItemId) {
        final Reference reference = referenceGenomeDao.loadReferenceGenomeByBioItemId(dataItemId);
        Assert.notNull(reference, getMessage(MessageCode.NO_SUCH_REFERENCE));

        if (reference.getGeneFile() != null) {
            reference.setGeneFile(geneFileDao.loadGeneFile(reference.getGeneFile().getId()));
        }
        reference.setAnnotationFiles(getAnnotationFilesByReferenceId(reference.getId()));
        return reference;
    }

    /**
     * Returns {@code List} of all reference genomes which has been registered in the system before now.
     * Each {@code Reference} entity provides all major metadata about a corresponded reference genome,
     * excluding the list of chromosomes.
     *
     * @return {@code List} list of reference genomes that are available in the system now
     */

    @Transactional(propagation = Propagation.SUPPORTS)
    public List<Reference> loadAllReferenceGenomes() {
        return loadAllReferenceGenomes(null);
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public List<Reference> loadAllReferenceGenomes(String referenceName) {
        if (!StringUtils.isEmpty(referenceName)) {
            Reference reference = referenceGenomeDao.loadReferenceGenomeByName(referenceName.toLowerCase());
            if (reference.getGeneFile() != null && reference.getGeneFile().getId() != null) {
                GeneFile geneFile = geneFileDao.loadGeneFile(reference.getGeneFile().getId());
                reference.setGeneFile(geneFile);
                reference.setAnnotationFiles(getAnnotationFilesByReferenceId(reference.getId()));
            }
            return Collections.singletonList(reference);
        }
        List<Reference> references = referenceGenomeDao.loadAllReferenceGenomes();
        Map<Long, List<Reference>> referenceToGeneIds = references.stream().collect(new ListMapCollector<>(
            reference -> reference.getGeneFile() != null ? reference.getGeneFile().getId() : null));

        List<GeneFile> geneFiles = geneFileDao.loadGeneFiles(referenceToGeneIds.keySet());
        List<Reference> result = new ArrayList<>(references.size());
        for (GeneFile geneFile : geneFiles) {
            referenceToGeneIds.get(geneFile.getId()).forEach(r -> {
                r.setGeneFile(geneFile);
                r.setAnnotationFiles(getAnnotationFilesByReferenceId(r.getId()));
            });
            result.addAll(referenceToGeneIds.get(geneFile.getId()));
        }

        if (referenceToGeneIds.containsKey(null)) {
            List<Reference> refsWithoutGeneFile = referenceToGeneIds.get(null);
            refsWithoutGeneFile.forEach(reference -> reference.setAnnotationFiles(
                    getAnnotationFilesByReferenceId(reference.getId())
            ));
            result.addAll(refsWithoutGeneFile);
        }

        return result;
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public List<Reference> loadAllReferenceGenomesByTaxId(final Long taxId) {
        return ListUtils.emptyIfNull(referenceGenomeDao.loadReferenceGenomesByTaxId(taxId))
                .stream().peek(ref -> {
                    if (ref.getGeneFile() != null && ref.getGeneFile().getId() != null) {
                        GeneFile geneFile = geneFileDao.loadGeneFile(ref.getGeneFile().getId());
                        ref.setGeneFile(geneFile);
                        ref.setAnnotationFiles(getAnnotationFilesByReferenceId(ref.getId()));
                    }
                }).collect(Collectors.toList());
    }

    /**
     * Returns {@code Chromosome} entity that includes major meta information about a single chromosome
     * associated with the given ID.
     *
     * @param chromosomeId {@code Long} specifies ID of a chromosome which metadata should be loaded
     * @return {@code Chromosome}
     * @throws IllegalArgumentException will be thrown in a case, if no chromosome with the given ID can be
     *                                  found in the system
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public Chromosome loadChromosome(final Long chromosomeId) {
        final Chromosome chromosome = referenceGenomeDao.loadChromosome(chromosomeId);
        Assert.notNull(chromosome, getMessage(MessageCode.NO_SUCH_CHROMOSOME));
        return chromosome;
    }

    /**
     * Returns {@code List} of chromosomes associated with the given reference ID and ordered by in the
     * alphabetical order.
     *
     * @param referenceId {@code Long} specifies ID of a reference genome which chromosomes should be loaded
     * @return {@code List}
     * @throws IllegalArgumentException will be thrown in a case, if no chromosome associated with the given
     *                                  reference ID can be found in the system
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public List<Chromosome> loadChromosomes(final Long referenceId) {
        final List<Chromosome> chromosomes = referenceGenomeDao.loadAllChromosomesByReferenceId(referenceId);
        Assert.isTrue(CollectionUtils.isNotEmpty(chromosomes), getMessage(MessageCode.NO_SUCH_REFERENCE));
        return chromosomes;
    }

    /**
     * Returns {@code List} of BaseEntity - information about files in system
     *
     * @param referenceId {@code Long} specifies ID of a reference genome which chromosomes should be loaded
     * @return {@code List}
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public List<BaseEntity> loadAllFile(final Long referenceId) {
        return referenceGenomeDao.loadAllFileByReferenceId(referenceId);
    }

    /**
     * Returns {@code Reference} entity associated with the given ID.
     *
     * @param referenceId {@code Long} specifies ID of a reference genome which metadata should be loaded
     * @return {@code Reference} specifies a reference genome metadata
     * @throws IllegalArgumentException will be thrown in a case, if no reference with the given ID can be
     *                                  found in the system
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public Reference getOnlyReference(final Long referenceId) {
        Assert.notNull(referenceId, getMessage(MessageCode.NO_SUCH_REFERENCE));
        final Reference reference = referenceGenomeDao.loadReferenceGenome(referenceId);
        Assert.notNull(reference, getMessage(MessageCode.NO_SUCH_REFERENCE));
        return reference;
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public List<Long> loadReferenceIdsByAnnotationFileId(final Long annotationId) {
        return referenceGenomeDao.loadGenomeIdsByAnnotationDataItemId(annotationId);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Reference updateReferenceGeneFileId(long referenceId, Long geneFileId) {
        final Reference reference = referenceGenomeDao.loadReferenceGenome(referenceId);
        Assert.notNull(reference, getMessage(MessageCode.NO_SUCH_REFERENCE));

        referenceGenomeDao.updateReferenceGeneFileId(referenceId, geneFileId);
        return load(referenceId);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Reference updateSpecies(long referenceId, String speciesVersion) {
        final Reference reference = referenceGenomeDao.loadReferenceGenome(referenceId);
        Assert.notNull(reference, getMessage(MessageCode.NO_SUCH_REFERENCE));
        if (speciesVersion != null) {
            final Species species = speciesDao.loadSpeciesByVersion(speciesVersion);
            Assert.notNull(species, getMessage(MessageCode.NO_SUCH_SPECIES, speciesVersion));
        }

        referenceGenomeDao.updateSpecies(referenceId, speciesVersion);
        return load(referenceId);
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public boolean isRegistered(Long id) {
        return referenceGenomeDao.loadReferenceGenome(id) != null;
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public List<BiologicalDataItem> getReferenceAnnotationFiles(Long referenceId) {
        List<Long> biologicalDataIds = referenceGenomeDao.loadAnnotationFileIdsByReferenceId(referenceId);
        return biologicalDataItemDao.loadBiologicalDataItemsByIds(biologicalDataIds);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Reference updateReferenceAnnotationFile(Long referenceId, Long annotationFileBiologicalItemId,
                                                   Boolean remove) throws IOException, FeatureIndexException {
        BiologicalDataItem annotationFile = fetchAnnotationFile(annotationFileBiologicalItemId);
        List<Long> genomeAnnotationIds = referenceGenomeDao.loadAnnotationFileIdsByReferenceId(referenceId);
        if (remove) {
            //check that we have this biological item as annotation file for this genome
            Assert.isTrue(
                    genomeAnnotationIds.contains(annotationFileBiologicalItemId),
                    getMessage(
                            MessagesConstants.ERROR_ANNOTATION_FILE_NOT_EXIST,
                            annotationFile.getName(),
                            load(referenceId).getName()
                    )
            );
            referenceGenomeDao.removeAnnotationFile(referenceId, annotationFileBiologicalItemId);
        } else {
            //check that we haven't yet this biological item as annotation file for this genome
            Assert.isTrue(
                    !genomeAnnotationIds.contains(annotationFileBiologicalItemId),
                    getMessage(
                            MessagesConstants.ERROR_ANNOTATION_FILE_ALREADY_EXIST,
                            annotationFile.getName(),
                            load(referenceId).getName()
                    )
            );
            if (annotationFile instanceof FeatureFile) {
                FeatureFile featureFile = (FeatureFile)annotationFile;
                Assert.isTrue(
                        featureFile.getReferenceId().equals(referenceId),
                        getMessage(
                                MessagesConstants.ERROR_ILLEGAL_REFERENCE_FOR_ANNOTATION,
                                load(featureFile.getReferenceId()).getName(),
                                load(referenceId).getName()
                        )
                );
            }
            referenceGenomeDao.addAnnotationFile(referenceId, annotationFileBiologicalItemId);
        }
        return load(referenceId);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Species registerSpecies(Species species) {
        Assert.isTrue(!StringUtils.isEmpty(species.getName()));
        Assert.isTrue(!StringUtils.isEmpty(species.getVersion()));
        Species registeredSpecies = speciesDao.loadSpeciesByVersion(species.getVersion());
        Assert.isNull(registeredSpecies,
                getMessage(MessagesConstants.ERROR_SPECIES_EXISTS, species.getVersion()));
        return speciesDao.saveSpecies(species);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Species updateSpecies(final Species species) {
        Assert.notNull(speciesDao.loadSpeciesByVersion(species.getVersion()),
                getMessage(MessagesConstants.ERROR_NO_SUCH_SPECIES, species.getVersion()));
        return speciesDao.updateSpecies(species);
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public List<Species> loadAllSpecies() {
        return speciesDao.loadAllSpecies();
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public Species loadSpeciesByVersion(String version) {
        return speciesDao.loadSpeciesByVersion(version);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Species unregisterSpecies(String speciesVersion) {
        Assert.notNull(speciesVersion, MessagesConstants.ERROR_INVALID_PARAM);
        Species species = speciesDao.loadSpeciesByVersion(speciesVersion);
        Assert.notNull(species, getMessage(MessagesConstants.ERROR_NO_SUCH_SPECIES, speciesVersion));
        speciesDao.deleteSpecies(species);

        return species;
    }

    private BiologicalDataItem fetchAnnotationFile(Long annotationFileId) {
        List<BiologicalDataItem> annotationFiles = biologicalDataItemDao.loadBiologicalDataItemsByIds(
                Collections.singletonList(annotationFileId));
        Assert.notNull(annotationFiles, getMessage(MessagesConstants.ERROR_BIO_ID_NOT_FOUND, annotationFileId));
        Assert.isTrue(
                !annotationFiles.isEmpty(),
                getMessage(MessagesConstants.ERROR_BIO_ID_NOT_FOUND, annotationFileId)
        );

        BiologicalDataItem annotationFile = annotationFiles.get(0);
        Assert.isTrue(ANNOTATION_FORMATS.contains(annotationFile.getFormat()),
                getMessage(MessagesConstants.ERROR_ILLEGAL_FEATURE_FILE_FORMAT, annotationFile.getPath())
        );
        return annotationFile;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public AbstractSecuredEntity changeOwner(Long id, String owner) {
        Reference file = load(id);
        biologicalDataItemDao.updateOwner(file.getBioDataItemId(), owner);
        file.setOwner(owner);
        return file;
    }

    @Override
    public AclClass getSupportedClass() {
        return AclClass.REFERENCE;
    }

}
