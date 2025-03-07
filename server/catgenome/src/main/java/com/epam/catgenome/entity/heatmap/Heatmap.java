/*
 * MIT License
 *
 * Copyright (c) 2021 EPAM Systems
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
package com.epam.catgenome.entity.heatmap;

import com.epam.catgenome.entity.BiologicalDataItem;
import com.epam.catgenome.entity.security.AclClass;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Set;

@Getter
@Setter
@Builder
public class Heatmap extends BiologicalDataItem {
    private Long heatmapId;
    private Long bioDataItemId;
    private String rowTreePath;
    private String columnTreePath;
    private String cellAnnotationPath;
    private HeatmapAnnotationType cellAnnotationType;
    private String labelAnnotationPath;
    private HeatmapAnnotationType rowAnnotationType;
    private HeatmapAnnotationType columnAnnotationType;
    private Set<?> cellValues;
    private HeatmapDataType cellValueType;
    private Double maxCellValue;
    private Double minCellValue;
    private List<List<String>> columnLabels;
    private List<List<String>> rowLabels;

    @Override
    public AclClass getAclClass() {
        return  AclClass.HEATMAP;
    }
}