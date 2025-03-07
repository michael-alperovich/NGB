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

package com.epam.catgenome.manager.bed;

import java.util.List;
import java.util.stream.Collectors;

import com.epam.catgenome.dao.reference.ReferenceGenomeDao;
import com.epam.catgenome.entity.security.AbstractSecuredEntity;
import com.epam.catgenome.entity.security.AclClass;
import com.epam.catgenome.manager.SecuredEntityManager;
import com.epam.catgenome.manager.metadata.MetadataManager;
import com.epam.catgenome.security.acl.aspect.AclSync;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import com.epam.catgenome.component.MessageHelper;
import com.epam.catgenome.constant.MessagesConstants;
import com.epam.catgenome.dao.BiologicalDataItemDao;
import com.epam.catgenome.dao.bed.BedFileDao;
import com.epam.catgenome.dao.project.ProjectDao;
import com.epam.catgenome.entity.BaseEntity;
import com.epam.catgenome.entity.bed.BedFile;
import com.epam.catgenome.entity.project.Project;

/**
 * Provides service for managing {@code BedFile} in the system
 */
@AclSync
@Service
public class BedFileManager implements SecuredEntityManager {

    @Autowired
    private BiologicalDataItemDao biologicalDataItemDao;

    @Autowired
    private BedFileDao bedFileDao;

    @Autowired
    private ReferenceGenomeDao referenceGenomeDao;

    @Autowired
    private ProjectDao projectDao;

    @Autowired
    private MetadataManager metadataManager;

    /**
     * Persists a {@code BedFile} record in the database
     * @param bedFile a {@code BedFile} instance to be persisted
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void create(BedFile bedFile) {
        bedFileDao.createBedFile(bedFile);
    }

    /**
     * Loads a persisted {@code BedFile} record by it's ID
     *
     * @param bedFileId {@code long} a BedFile ID
     * @return {@code BedFile} instance
     */
    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public BedFile load(Long bedFileId) {
        return bedFileDao.loadBedFile(bedFileId);
    }

    /**
     * Creates a new ID for a {@code BedFile} instance
     *
     * @return {@code Long} new {@code BedFile} ID
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Long createBedFileId() {
        return bedFileDao.createBedFileId();
    }

    /**
     * Deletes {@code BedFile} record, specified by ID
     *
     * @param bedFile BedFile to delete
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void delete(BedFile bedFile) {
        List<Project> projectsWhereFileInUse = projectDao.loadProjectsByBioDataItemId(bedFile.getBioDataItemId());
        List<Long> genomeIdsByAnnotation = referenceGenomeDao.
                loadGenomeIdsByAnnotationDataItemId(bedFile.getBioDataItemId());

        Assert.isTrue(genomeIdsByAnnotation.isEmpty(),
                MessageHelper.getMessage(
                        MessagesConstants.ERROR_FILE_IN_USE_AS_ANNOTATION,
                        bedFile.getName(),
                        bedFile.getId(),
                        genomeIdsByAnnotation
                                .stream()
                                .map(referenceGenomeDao::loadReferenceGenome)
                                .map(BaseEntity::getName)
                                .collect(Collectors.joining(", "))
                )
        );

        Assert.isTrue(projectsWhereFileInUse.isEmpty(), MessageHelper.getMessage(MessagesConstants.ERROR_FILE_IN_USE,
                bedFile.getName(), bedFile.getId(), projectsWhereFileInUse.stream().map(BaseEntity::getName)
                        .collect(Collectors.joining(", ")))
        );

        bedFileDao.deleteBedFile(bedFile.getId());
        biologicalDataItemDao.deleteBiologicalDataItem(bedFile.getIndex().getId());
        biologicalDataItemDao.deleteBiologicalDataItem(bedFile.getBioDataItemId());
        metadataManager.delete(bedFile);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public AbstractSecuredEntity changeOwner(Long id, String owner) {
        BedFile file = load(id);
        biologicalDataItemDao.updateOwner(file.getBioDataItemId(), owner);
        file.setOwner(owner);
        return file;
    }

    @Override
    public AclClass getSupportedClass() {
        return AclClass.BED;
    }

}
