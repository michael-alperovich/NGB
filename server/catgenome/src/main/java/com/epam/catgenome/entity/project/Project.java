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

package com.epam.catgenome.entity.project;

import java.util.*;
import java.util.stream.Collectors;

import com.epam.catgenome.entity.BiologicalDataItem;
import com.epam.catgenome.entity.BiologicalDataItemFormat;
import com.epam.catgenome.entity.security.AbstractHierarchicalEntity;
import com.epam.catgenome.entity.security.AbstractSecuredEntity;
import com.epam.catgenome.entity.security.AclClass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.glassfish.grizzly.http.Note;
import org.springframework.util.CollectionUtils;

/**
 * Source:      Project
 * Created:     21.12.15, 18:16
 * Project:     CATGenome Browser
 * Make:        IntelliJ IDEA 14.1.4, JDK 1.8
 *
 * <p>
 * Represents a project entity, that describes user's workspace with tracks, that he worked with.
 * </p>
 */
@NoArgsConstructor
@Getter
@Setter
public class Project extends AbstractHierarchicalEntity {

    private List<ProjectItem> items;
    private Integer itemsCount;
    private Map<BiologicalDataItemFormat, Integer> itemsCountPerFormat;
    private Date lastOpenedDate;
    private List<Project> nestedProjects;
    private Long parentId;
    private List<Note> notes;

    public Project(Long id) {
        super(id);
    }

    @Override
    public List<? extends BiologicalDataItem> getLeaves() {
        return items == null
                ? Collections.emptyList()
                : items.stream().map(ProjectItem::getBioDataItem).collect(Collectors.toList());
    }

    @Override
    public List<? extends AbstractHierarchicalEntity> getChildren() {
        return nestedProjects == null ? Collections.emptyList() : nestedProjects;
    }

    @Override
    public void filterLeaves(Map<AclClass, Set<Long>> idToRemove) {
        if (CollectionUtils.isEmpty(items)) {
            return;
        }

        Set<ProjectItem> toRemove = new HashSet<>();
        for (ProjectItem item : items) {
            BiologicalDataItem leaf = item.getBioDataItem();
            if (leaf.getFormat() == BiologicalDataItemFormat.REFERENCE
                    || leaf.getFormat() == BiologicalDataItemFormat.REFERENCE_INDEX) {
                continue;
            }

            Set<Long> ids = idToRemove.get(leaf.getAclClass());
            if (!CollectionUtils.isEmpty(ids) && ids.contains(leaf.getId())) {
                toRemove.add(item);
            }
        }
        items.removeAll(toRemove);
    }

    @Override
    public void filterChildren(Map<AclClass, Set<Long>> idToRemove) {
        if (nestedProjects == null) {
            return;
        }

        Set<Long> ids = idToRemove.get(AclClass.PROJECT);
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        nestedProjects = nestedProjects.stream()
                .filter(item -> !ids.contains(item.getId()))
                .collect(Collectors.toList());
    }

    @Override
    public void clearForReadOnlyView() {
        itemsCount = 0;
        itemsCountPerFormat = new HashMap<>();

        if (items == null) {
            return;
        }

        for (ProjectItem item : items) {
            itemsCount++;
            BiologicalDataItemFormat itemFormat = item.getBioDataItem().getFormat();
            itemsCountPerFormat.putIfAbsent(itemFormat, 0);
            itemsCountPerFormat.computeIfPresent(itemFormat, (k, i) -> i + 1);
        }
    }

    @Override
    public AbstractSecuredEntity getParent() {
        return parentId != null ? new Project(parentId) : null;
    }

    @Override
    public AclClass getAclClass() {
        return  AclClass.PROJECT;
    }
}
