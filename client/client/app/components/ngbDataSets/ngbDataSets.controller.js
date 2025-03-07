import baseController from '../../shared/baseController';
import ngbDataSetsService, {toPlainList} from './ngbDataSets.service';

export default class ngbDataSetsController extends baseController {
    static get UID() {
        return 'ngbDataSetsController';
    }

    searchPattern = null;
    nothingFound = null;
    noDatasets = false;
    _isLoading = true;
    showTrackOriginalName = true;

    constructor(
        $mdDialog,
        $scope,
        $element,
        $timeout,
        dispatcher,
        ngbDataSetsService,
        projectContext,
        trackNamingService,
        localDataService,
        appLayout
    ) {
        super();
        Object.assign(this, {
            $element,
            $mdDialog,
            $scope,
            $timeout,
            dispatcher,
            localDataService,
            projectContext,
            service: ngbDataSetsService,
            trackNamingService,
            appLayout
        });
        $scope.$watch('$ctrl.searchPattern', ::this.searchPatternChanged);
        this.initEvents();
        this._isLoading = true;
        this.showTrackOriginalName = this.localDataService.getSettings().showTrackOriginalName;

        const self = this;
        this.tracksStateChangeListener = async () => {
            await self.service.updateSelectionFromState(self.datasets);
        };
        const tracksStateChangeListener = ::this.tracksStateChangeListener;
        const globalSettingsChangedHandler = (state) => {
            self.showTrackOriginalName = state.showTrackOriginalName;
        };
        const trackNameChanged = () => {
            $timeout(::$scope.$apply);
        };
        dispatcher.on('tracks:state:change', tracksStateChangeListener);
        dispatcher.on('settings:change', globalSettingsChangedHandler);
        dispatcher.on('track:custom:name', trackNameChanged);
        $scope.$on('$destroy', () => {
            dispatcher.removeListener('tracks:state:change', tracksStateChangeListener);
            dispatcher.removeListener('settings:change', globalSettingsChangedHandler);
            dispatcher.removeListener('track:custom:name', trackNameChanged);
        });
    }

    events = {
        'activeDataSets': ::this.onResize,
        'datasets:filter:changed': ::this.loadingFinished,
        'datasets:loading:finished': ::this.loadingFinished,
        'datasets:loading:started': ::this.loadingStarted,
        'reference:change': ::this.onProjectChanged,
    };

    async $onInit() {
        await this.refreshDatasets();
    }

    async refreshDatasets() {
        await this.projectContext.refreshDatasets();
        this.searchPatternChanged();
    }

    async onProjectChanged() {
        await this.service.updateSelectionFromState(this.datasets);
        this.onResize();
    }

    async loadingStarted() {
        this._isLoading = true;
        this.$timeout(::this.$scope.$apply);
    }
    
    async loadingFinished() {
        if (!this.projectContext.datasetsAreLoading) {
            this.datasets = await this.service.getDatasets();
            this.nothingFound = false;
        }
        this.noDatasets = !this.datasets || this.datasets.length === 0;
        this._isLoading = !this.projectContext.datasetsLoaded;
        this.$timeout(::this.$scope.$apply);
        this.onResize();
    }

    get datasetsPlain() {
        return toPlainList(this.datasets, this.trackNamingService);
    }

    get isLoading() {
        return this._isLoading;
    }

    get maxTracksToOpen () {
        const settings = this.projectContext.getTrackDefaultSettings('settings') || {};
        return settings.max_tracks_count || 10;
    }

    get searchLength() {
        if (!this.searchPattern) {
            return 0;
        }
        return this.searchPattern.length;
    }

    getNodeHint (node) {
        if (node.isProject && node.totalFilesCount > this.maxTracksToOpen) {
            return `${node.hint}

Dataset has ${node.totalFilesCount} files that exceeds limit (${this.maxTracksToOpen}).
Open dataset files manually.`;
        }
        return node.hint;
    }

    getCustomName(node) {
        if (!node.isProject) {
            return this.trackNamingService.getCustomName(node);
        }
        return null;
    }

    getTemplateNode(node) {
        if (node.isProject)
            return 'ngbDataSetsParentNode.tpl.html';
        else
            return 'ngbDataSetsTerminalNode.tpl.html';
    }

    hiddenReference (reference) {
        return reference &&
            this.service.isDummyReference(reference.name);
    }

    datasetSelectionDisabled (dataset) {
        const isDummyReference = dataset &&
            this.hiddenReference(dataset.reference);
        return dataset.totalFilesCount > this.maxTracksToOpen || isDummyReference;
    }

    async select(item, isSelected, tree) {
        const self = this;
        if (!self.service.checkSelectionAvailable(item, isSelected)) {
            const reference = self.service.getItemReference(item);
            this.$timeout(() => {
                self.service.deselectItem(item);
                self.$scope.$apply();
            });
            const confirm = self.$mdDialog.confirm()
                .title(`Switch reference ${self.projectContext.reference ? self.projectContext.reference.name : ''}${reference ? ` to ${reference.name}` : ''}?`)
                .textContent('All opened tracks will be closed.')
                .ariaLabel('Change reference')
                .ok('OK')
                .cancel('Cancel');
            self.$mdDialog.show(confirm).then(function () {
                self.service.selectItem(item, isSelected, tree);
            }, function () {
            });
        } else {
            this.$timeout(() => {
                self.service.selectItem(item, isSelected, tree);
                self.$scope.$apply();
            });
        }
    }


    searchPatternChanged() {
        if (this.datasets) {
            this.nothingFound = !ngbDataSetsService.search(this.searchPattern || '', this.datasets);
        } else {
            this.nothingFound = false;
        }
    }


    nameIsChanged(node) {
        if (!node.isProject) {
            return this.trackNamingService.nameChanged(node);
        }
        return false;
    }

    onResize() {
        this.$timeout(() => {
            this.$element.resize();
        });
    }

    toggleSelected(node) {
        node.__selected = !node.__selected;
        this.toggle(node);
    }

    toggle(node) {
        if (node.__selected) {
            node.__expanded = true;
        }
        if (node.isProject && !node.__selected) {
            this.service.deselectItem(node);
        }

        this.select(node, node.__selected, this.datasets);
    }

    expanded(node) {
        node.__expanded = !node.__expanded;
        this.service.toggle(node);
    }

    filter() {
        return this.service.filter;
    }
    
    clearSearch() {
        this.searchPattern = null;
    }
}
