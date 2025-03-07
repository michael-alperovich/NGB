import parseCoordinates, {getCoordinatesText} from './parse-coordinates';
import $ from 'jquery';
import SearchResults from './ngbCoordinates.search.results';
import angular from 'angular';
import baseController from '../../../../shared/baseController';

export default class ngbCoordinatesController extends baseController {

    static get UID() {
        return 'ngbCoordinatesController';
    }

    isChromosomeEditingMode = false;
    isCoordinatesEditingMode = false;

    isEnabled = true;

    chromosomeText = null;
    coordinatesText = null;

    hints = [];
    contextMenuItems = [];

    searchResult = new SearchResults();

    dispatcher;
    projectContext;

    get isEditingMode() {
        return this.isChromosomeEditingMode || this.isCoordinatesEditingMode;
    }

    /* @ngInject */
    constructor(dispatcher, projectContext, $scope, $timeout, $element, genomeDataService, projectDataService) {
        super();

        Object.assign(this, {
            $element,
            $scope,
            $timeout,
            dispatcher,
            genomeDataService,
            projectContext,
            projectDataService
        });

        if (this.browserId) {
            this.isEnabled = false;
            this.coordinatesText = null;
            this.chromosomeText = null;
            this._createTitleBrowserId();

            const event = `viewport:position:${this.browserId}`;

            this.events = {
                [event]: ::this._createTitleBrowserId
            };

        } else {
            this.events = {
                'state:change': ::this.initialization
            };
        }
        this.initEvents();
    }

    get isProjectSelected() {
        return this.projectContext.reference !== null;
    }

    $onInit() {
        this.initialization();
    }

    initialization() {
        if (this.isProjectSelected) {
            this.chromosomes = this.projectContext.chromosomes;
            this.coordinatesText = null;
            this.chromosomeText = null;
            this._createTitle();
        }
    }

    startEdit(mode, e) {
        if (e) {
            e.preventDefault();
            e.stopPropagation();
            e.stopImmediatePropagation();
        }
        if (!this.isEnabled) {
            return;
        }
        this.searchResult = new SearchResults();
        switch (mode) {
            case 'chromosome':
                this.isChromosomeEditingMode = true;
                break;
            case 'coordinates':
                this.isCoordinatesEditingMode = true;
                break;
        }
        this.inputChanged(mode, true);
    }

    chromosomeInputChanged(initial) {
        if (this.isChromosomeEditingMode) {
            this.hints = [];
            const searchText = initial ? null : ((this.chromosomeText && this.chromosomeText.length > 0) ? this.chromosomeText : null);
            const chrPrefix = 'chr';
            const mapChromosomeFn = function(chromosome) {
                let modifiedName = chromosome.name.toLowerCase();
                if (modifiedName.startsWith(chrPrefix)) {
                    modifiedName = modifiedName.substr(chrPrefix.length).trim();
                }
                let chromosomeIndex = +modifiedName;
                if (isNaN(chromosomeIndex)) {
                    chromosomeIndex = null;
                }
                return {
                    chromosomeIndex,
                    chromosomeName: modifiedName,
                    name: chromosome.name,
                    tag: chromosome,
                    type: 'chromosome'
                };
            };
            const sortChromosomesFn = function(chr1, chr2) {
                if (chr1.chromosomeIndex !== null && chr2.chromosomeIndex !== null) {
                    if (chr1.chromosomeIndex < chr2.chromosomeIndex) {
                        return -1;
                    } else if (chr1.chromosomeIndex > chr2.chromosomeIndex) {
                        return 1;
                    }
                } else if (chr1.chromosomeIndex !== null) {
                    return -1;
                } else if (chr2.chromosomeIndex !== null) {
                    return 1;
                }
                if (chr1.chromosomeName.length < chr2.chromosomeName.length) {
                    return -1;
                } else if (chr1.chromosomeName.length > chr2.chromosomeName.length) {
                    return 1;
                }
                if (chr1.chromosomeName < chr2.chromosomeName) {
                    return -1;
                } else if (chr1.chromosomeName > chr2.chromosomeName) {
                    return 1;
                }
                return 0;
            };
            const clearChromosomeSelectionItem = {
                name: 'NONE',
                tag: null,
                type: 'chromosome'
            };
            this.contextMenuItems = (this.filterChromosome(searchText) || []).map(mapChromosomeFn);
            this.contextMenuItems.sort(sortChromosomesFn);
            this.contextMenuItems.splice(0, 0, clearChromosomeSelectionItem);
            this.searchResult = new SearchResults(this.hints, this.contextMenuItems);
        }
    }

    coordinatesInputChanged() {
        if (this.isCoordinatesEditingMode) {
            this.hints = [];
            this.hints.push('Enter a position at the chromosome or the name of a bookmark or a feature (gene, mRNA etc.)');
            if (!this.coordinatesText || this.coordinatesText.indexOf(':') >= 0) {
                this.contextMenuItems = [];
                this.searchResult = new SearchResults(this.hints, this.contextMenuItems);
            }
            else {
                (async() => {
                    await this.searchGenes(this.coordinatesText);
                    this.searchResult = new SearchResults(this.hints, this.contextMenuItems);
                    this.$scope.$apply();
                })();
            }
        }
    }

    inputChanged(mode, initial = false) {
        switch (mode) {
            case 'chromosome':
                this.chromosomeInputChanged(initial);
                break;
            case 'coordinates':
                this.coordinatesInputChanged();
                break;
        }
    }

    preventGoldenLayoutPanelDrag(event) {
        for (let i = 0; i < event.originalEvent.path.length; i++) {
            if ($(event.originalEvent.path[i]).hasClass('coordinates-menu-item')) {
                return;
            }
        }
        event.stopImmediatePropagation();
    }

    async searchGenes(text) {
        if (!this.projectContext.reference) {
            return;
        }
        const searchQuery = text;
        const result = await this.projectDataService.searchGenes(this.projectContext.reference.id, searchQuery);
        if (searchQuery !== this.coordinatesText) {
            return;
        }
        if (result.entries && result.entries.length > 0) {
            this.hints = [];
            this.hints.push(`Search results (${result.totalResultsCount}):`);
            if (result.exceedsLimit) {
                this.hints.push(`Too much results. Top ${result.entries.length} are shown`);
            }
            this.contextMenuItems = result.entries.map(x => {
                const featureType = x.featureType ? x.featureType.toLowerCase() : '';
                return {
                    featureId: x.featureId,
                    featureType: featureType,
                    name: x.featureName,
                    subTitle: featureType + (featureType !== 'bookmark' ? `, ${x.featureId}` : ''),
                    tag: x,
                    type: 'feature'
                };
            });
        }
        else {
            this.hints = [];
            if (!searchQuery || searchQuery.length < 2) {
                this.hints.push('Search string must have more than one symbol');
            }
            else {
                this.hints.push('No genes are found');
            }
            this.contextMenuItems = [];
        }
    }

    didTypeChromosome() {
        if (!this.chromosomeText || this.chromosomeText === '') {
            this.onChromosomeChange(null);
            this.isChromosomeEditingMode = false;
            this.isCoordinatesEditingMode = false;
            this.searchResult = new SearchResults();
        }
        else {
            const result = this.chromosomes.filter(x => x.name.toLowerCase() === (this.chromosomeText || '').toLowerCase());
            if (result && result.length === 1) {
                this.onChromosomeChange(result[0]);
                this.isChromosomeEditingMode = false;
                this.isCoordinatesEditingMode = false;
                this.searchResult = new SearchResults();
            }
            else {
                this.discardEdit();
            }
        }
    }

    chromosomeKeyUp(event) {
        if (!event) {
            return;
        }
        if (/^enter$/i.test(event.key)) {
            this.didTypeChromosome();
        } else if (/^(esc|escape)$/i.test(event.key)) {
            this.discardEdit();
        }
    }

    coordinatesKeyUp(event) {
        if (!event) {
            return;
        }
        if (/^enter$/i.test(event.key)) {
            this.didTypeCoordinates();
        } else if (/^(esc|escape)$/i.test(event.key)) {
            this.discardEdit();
        }
    }

    didTypeCoordinates() {
        (async()=> {
            const parseResult = await this._parseCoordinates();
            if (parseResult) {
                this._createTitle();
                this.isChromosomeEditingMode = false;
                this.isCoordinatesEditingMode = false;
            }
        })();
    }

    didSelectSearchItem(item) {
        this.isChromosomeEditingMode = false;
        this.isCoordinatesEditingMode = false;
        switch (item.type) {
            case 'chromosome': {
                if (!item.tag || `${this.chromosomeName}` !== `${item.tag.name}`) {
                    this.onChromosomeChange(item.tag);
                }
            }
                break;
            case 'feature': {
                if (item.tag.chromosome && this.chromosomeName !== item.tag.chromosome.name) {
                    this.projectContext.changeState({
                        chromosome: item.tag.chromosome,
                        viewport: {
                            end: item.tag.endIndex,
                            start: item.tag.startIndex
                        }
                    });
                }
                else {
                    this.projectContext.changeState({
                        viewport: {
                            end: item.tag.endIndex,
                            start: item.tag.startIndex
                        }
                    });
                }
            }
                break;
        }
    }

    discardEdit() {
        this._createTitle();
        this.isChromosomeEditingMode = false;
        this.isCoordinatesEditingMode = false;
        this.searchResult = new SearchResults();
    }

    onChromosomeChange(value) {
        this.projectContext.changeState({chromosome: value});
    }

    filterChromosome(query) {
        return query ? this.chromosomes.filter(createFilterFor(query)) : this.chromosomes;
    }

    async _createTitleBrowserId() {
        if (!this.isProjectSelected) {
            return;
        }
        const chromosome = this.projectContext.getChromosome({name: this.chromosomeName});
        const chr = chromosome.name;

        const viewportObj = this.projectContext.viewports[this.browserId];
        if (viewportObj) {
            this.$timeout(() => {
                const {start, end} = viewportObj;
                this.coordinatesText = `${chr}: ${parseInt(start)} - ${parseInt(end)}`;
                this.chromosomeText = chr;
            });
        }

    }

    async _createTitle() {
        if (!this.projectContext.currentChromosome || !this.projectContext.reference) {
            this.coordinatesText = null;
            this.chromosomeText = null;
            return;
        }
        if (!this.chromosome || this.chromosome.name.toLowerCase() !== this.projectContext.currentChromosome.name.toLowerCase()) {
            this.chromosome = this.projectContext.currentChromosome;
        }
        const chr = this.chromosome.name;
        if (this.projectContext.viewport) {
            const {start, end}= this.projectContext.viewport;
            this.coordinatesText = `${chr}: ${parseInt(start)} - ${parseInt(end)}`;
            this.chromosomeText = chr;
        }
    }

    get chromosome() {
        return this._chr;
    }

    set chromosome(obj) {
        this._chr = obj;
    }

    async _parseCoordinates() {
        //1. '' - should open specified chromosome and range
        if (!this.coordinatesText) {
            if (this.chromosome) {
                const [start, end] = [1, this.chromosome.size];
                this.projectContext.changeState({viewport: {end, start}});
                return true;
            }
            return false;
        }
        const [
            currentBrowser,
            splitViewBrowser
        ] = await parseCoordinates(
            this.coordinatesText,
            this.projectContext,
            this.chromosome ? this.chromosome.name : undefined,
            this.contextMenuItems,
            this.projectDataService,
            this.projectContext.reference ? this.projectContext.reference.id : undefined
        ) || [];
        if (currentBrowser) {
            this.coordinatesText = getCoordinatesText(currentBrowser);
            this.projectContext.changeState(
                currentBrowser,
                false,
                () => {
                    if (splitViewBrowser && splitViewBrowser.chromosome && this.dispatcher) {
                        this.dispatcher.emitSimpleEvent('browser:open:split:view', splitViewBrowser);
                    }
                }
            );
            return true;
        }
        return false;
    }
}

function createFilterFor(query) {
    const lowercaseQuery = angular.lowercase(query);
    return function filterFn(chromosome) {
        return (angular.lowercase(chromosome.name).indexOf(lowercaseQuery) === 0);
    };
}
