import * as PIXI  from 'pixi.js-legacy';
import {ColorFormats} from '../../color-scheme';
import HeatmapGraphicsBase from './heatmap-graphics';
import HoveredRenderer from './hovered-renderer';
import InteractiveZone from '../../interactions/interactive-zone';
import cancellablePromise from './utilities/cancellable-promise';
import config from './config';
import events from '../../utilities/events';
import getDataItemTooltipContent from './utilities/get-data-item-tooltip-content';
import makeInitializable from '../../utilities/make-initializable';

const MASK_COLOR = 0xFF0000;
const ITERATIONS_PER_FRAME = 10000;
const RENDER_ON_BATCH_UPDATE_THRESHOLD = 10000;

const BLOCK_WIDTH = 512;
const BLOCK_HEIGHT = 512;

const PIXI_WEB_GL_RENDERER_TYPE = 1;

class HeatmapDataRenderer extends InteractiveZone {
    static RenderMode = {
        canvas: 'canvas',
        webGl: 'webGl'
    };

    /**
     *
     * @param {HeatmapViewport} viewport
     * @param {ColorScheme} colorScheme
     * @param {HeatmapData} data
     * @param {LabelsManager} labelsManager
     * @param {Object} renderer
     */
    constructor(
        viewport,
        colorScheme,
        data,
        labelsManager,
        renderer
    ) {
        super({priority: InteractiveZone.Priorities.data});
        makeInitializable(this);
        this.container = new PIXI.Container();
        this.viewport = viewport;
        this.colorScheme = colorScheme;
        this.data = data;
        this.labelsManager = labelsManager;
        this.session = {};
        /**
         * Heatmap blocks
         * @type {HeatmapGraphicsBase[]}
         */
        this.blocks = [];
        /**
         * Hovered graphics renderer
         * @type {HoveredRenderer}
         */
        this.hoveredRenderer = new HoveredRenderer(this.viewport, this.colorScheme);
        this.hoveredRenderer.onRequestRender(this.requestRender.bind(this));
        /**
         * If the data was processed
         * @type {boolean}
         */
        this.dataProcessed = true;
        this.dataLoadedListener = this.rebuild.bind(this);
        this.colorSchemeChangedListener = this.colorSchemeChanged.bind(this);
        this.data.onDataLoaded(this.dataLoadedListener);
        this.data.onColumnsRowsReordered(this.dataLoadedListener);
        this.colorScheme.onChanged(this.colorSchemeChangedListener);
        this.colorScheme.onInitialized(this.colorSchemeChangedListener);
        this.initialized = false;
        this._updating = false;
        this.mode = renderer && renderer.type === PIXI_WEB_GL_RENDERER_TYPE
            ? HeatmapDataRenderer.RenderMode.webGl
            : HeatmapDataRenderer.RenderMode.canvas;
    }

    destroy() {
        this.hoveredRenderer.destroy();
        if (this.data) {
            this.data.removeEventListeners(this.dataLoadedListener);
        }
        if (this.colorScheme) {
            this.colorScheme.removeEventListeners(this.colorSchemeChangedListener);
        }
        this.data = undefined;
        this.colorScheme = undefined;
        this.blocks.forEach(block => block.destroy());
        this.blocks = [];
        super.destroy();
    }

    get updating() {
        return this._updating || (this.blocks || []).filter(block => block.updating).length > 0;
    }

    initialize(renderer) {
        const cancelPrevious = typeof this.cancelRebuildBlocks === 'function'
            ? this.cancelRebuildBlocks()
            : Promise.resolve();
        cancelPrevious
            .then(() => {
                const previousMode = this.mode;
                this.mode = renderer && renderer.type === PIXI_WEB_GL_RENDERER_TYPE
                    ? HeatmapDataRenderer.RenderMode.webGl
                    : HeatmapDataRenderer.RenderMode.canvas;
                const modeChanged = previousMode !== this.mode;
                this.initialized = false;
                if (modeChanged) {
                    this.blocks.forEach(block => block.destroy());
                    this.blocks = [];
                }
                this.clearSession();
                this.container.removeChildren();
                this.updatingLabel = this.labelsManager
                    ? this.labelsManager.getLabel(config.updating.label.text, config.updating.label.font)
                    : undefined;
                this.background = new PIXI.Graphics();
                this.container.addChild(this.background);
                this.blocksContainer = new PIXI.Container();
                this.container.addChild(this.blocksContainer);
                this.container.addChild(this.hoveredRenderer.graphics);
                if (this.updatingLabel) {
                    this.container.addChild(this.updatingLabel);
                    this.updatingLabel.visible = false;
                }
                Promise
                    .all(this.blocks.map(block => block.initialize()))
                    .then(() => {
                        this.blocks.forEach(block => this.blocksContainer.addChild(block.container));
                        this.initialized = true;
                        this.requestRender();
                        if (modeChanged || !this.dataProcessed) {
                            this.rebuild();
                        }
                    });
            });
    }

    onUpdating(callback) {
        this.addEventListener(events.updating, callback);
    }

    onShowTooltip(callback) {
        this.addEventListener(events.tooltip.show, callback);
    }

    onHideTooltip(callback) {
        this.addEventListener(events.tooltip.hide, callback);
    }

    clearSession() {
        this.session = {};
    }

    colorSchemeChanged() {
        let changed = false;
        this.blocks.forEach(block => {
            changed = block.applyColorScheme(this.colorScheme) || changed;
        });
        this.session.colorSchemeApplyied = false;
        if (changed) {
            this.requestRender();
        }
    }

    rebuild() {
        this.dataProcessed = false;
        this.colorSchemeChanged();
        this.requestRender();
        this.cancelRebuildBlocks = cancellablePromise(
            this.rebuildBlocks.bind(this),
            this.cancelRebuildBlocks
        );
        this.requestRender();
    }

    handleUpdate() {
        this.emit(events.updating);
        this.requestRender();
    }

    /**
     *
     * @param {HeatmapDataItem} item
     * @param {Object|true} [create]
     * @param {boolean} [create.batch=true]
     * @returns {undefined|HeatmapGraphicsBase}
     */
    findBlockByItem(item, create) {
        if (!item) {
            return undefined;
        }
        const {
            column,
            row
        } = item;
        for (let b = 0; b < this.blocks.length; b += 1) {
            if (this.blocks[b].testDataItem(item)) {
                return this.blocks[b];
            }
        }
        if (create) {
            const {
                batch = true
            } = (typeof create === 'boolean' ? {} : create);
            const blockColumnStart = Math.floor(column / BLOCK_WIDTH) * BLOCK_WIDTH;
            const blockRowStart = Math.floor(row / BLOCK_HEIGHT) * BLOCK_HEIGHT;
            const block = HeatmapGraphicsBase.createInstance({
                column: blockColumnStart,
                row: blockRowStart,
                width: BLOCK_WIDTH,
                height: BLOCK_HEIGHT,
                viewport: this.viewport,
                colorScheme: this.colorScheme,
                renderWhileBuilding: !batch,
                CANVAS_RENDERER: this.mode === HeatmapDataRenderer.RenderMode.canvas
            });
            if (batch) {
                block.batchInsertStart();
            }
            block.onRequestRender(this.requestRender.bind(this));
            block.onUpdating(this.handleUpdate.bind(this));
            this.blocks.push(block);
            this.blocksContainer.addChild(block.container);
            return block;
        }
        return undefined;
    }

    rebuildBlocks(isCancelledFn) {
        if (isCancelledFn() || !this.data.dataReady) {
            return Promise.resolve();
        }
        return new Promise((resolve) => {
            this._updating = true;
            this.emit(events.updating);
            const generator = this.data.data.entries();
            const totalElements = this.data.data.count || Infinity;
            const previousColumnsRowsReordered = this.columnsRowsReordered;
            this.columnsRowsReordered = this.data && this.data.metadata
                ? this.data.metadata.columnsRowsReordered
                : false;
            const dendrogramModeChanged = previousColumnsRowsReordered !== this.columnsRowsReordered &&
                previousColumnsRowsReordered !== undefined;
            const batch = dendrogramModeChanged ||
                totalElements >= RENDER_ON_BATCH_UPDATE_THRESHOLD;
            if (batch) {
                this.blocks.forEach(block => block.batchInsertStart());
            }
            const frame = () => {
                /**
                 * @type {HeatmapGraphicsBase}
                 */
                let currentBlock;
                let i = 0;
                let item;
                let done = false;
                const next = () => {
                    const nextResult = generator.next();
                    done = nextResult.done;
                    item = nextResult.value;
                };
                do {
                    if (isCancelledFn()) {
                        break;
                    }
                    next();
                    if (!done && item) {
                        if (!currentBlock || !currentBlock.testDataItem(item)) {
                            currentBlock = this.findBlockByItem(item, {batch});
                        }
                        if (currentBlock) {
                            currentBlock.appendDataItem(item, batch);
                        }
                    }
                    i += 1;
                } while (i < ITERATIONS_PER_FRAME && !done);
                this.requestRender();
                this.dataProcessed = !isCancelledFn() && done;
                if (isCancelledFn() || done) {
                    if (batch && this.dataProcessed) {
                        this.blocks.forEach(block => block.batchUpdateDone(dendrogramModeChanged));
                    }
                    this._updating = false;
                    this.emit(events.updating);
                    resolve();
                } else {
                    setTimeout(frame, 0);
                }
            };
            frame();
        });
    }

    getSessionFlags() {
        const sizeChanged = this.session.width !== this.viewport.deviceWidth ||
            this.session.height !== this.viewport.deviceHeight;
        const positionChanged = this.session.column !== this.viewport.columns.center ||
            this.session.row !== this.viewport.rows.center;
        const colorSchemeChanged = !this.session.colorSchemeApplyied;
        const scaleChanged = this.session.scale !== this.viewport.scale.tickSize;
        const viewportChanged = sizeChanged || positionChanged || scaleChanged;
        const updating = this.updating;
        const updateStatusChanged = this.session.updating !== updating;
        return {
            colorSchemeChanged,
            viewportChanged,
            updateStatusChanged,
            changed: viewportChanged || updateStatusChanged || colorSchemeChanged
        };
    }

    updateSessionFlags() {
        this.session.colorSchemeApplyied = true;
        this.session.width = this.viewport.deviceWidth;
        this.session.height = this.viewport.deviceHeight;
        this.session.column = this.viewport.columns.center;
        this.session.row = this.viewport.rows.center;
        this.session.updating = this.updating;
    }

    test(event) {
        return event && event.fitsViewport();
    }

    onHover(event) {
        if (!event || !this.test(event) || !this.data || !this.data.dataReady || !this.viewport) {
            this.hoveredChanged = !!this.hovered;
            this.hovered = undefined;
            this.emit(events.tooltip.hide);
            if (this.hoveredChanged) {
                this.requestRender();
            }
        } else {
            const radius = this.viewport.scale.getScaleDimension(config.hover.checkRadius);
            const hover = (item) => {
                const {
                    column: currentColumn,
                    row: currentRow
                } = this.hovered || {};
                const {
                    column,
                    row
                } = item || {};
                this.hoveredChanged = currentColumn !== column || currentRow !== row;
                if (this.hoveredChanged) {
                    this.hovered = item;
                    this.emit(
                        this.hovered
                            ? events.tooltip.show
                            : events.tooltip.hide,
                        {
                            event,
                            content: getDataItemTooltipContent(this.hovered)
                        }
                    );
                    this.requestRender();
                }
            };
            const {value} = this.data.data.entriesWithinRadius(event, radius).next();
            hover(value);
        }
    }

    renderMask() {
        const {viewportChanged} = this.getSessionFlags();
        if (viewportChanged) {
            const mask = new PIXI.Graphics();
            mask.beginFill(MASK_COLOR, 1)
                .drawRect(
                    this.viewport.getGlobalCanvasPoint({x: 0}).x,
                    this.viewport.getGlobalCanvasPoint({y: 0}).y,
                    this.viewport.deviceWidth,
                    this.viewport.deviceHeight
                )
                .endFill();
            this.container.mask = mask;
        }
    }

    renderBackground() {
        const {
            viewportChanged,
            colorSchemeChanged
        } = this.getSessionFlags();
        if (viewportChanged || colorSchemeChanged) {
            this.background
                .clear()
                .beginFill(this.colorScheme.missingColor, 1)
                .drawRect(
                    0,
                    0,
                    this.viewport.deviceWidth,
                    this.viewport.deviceHeight
                )
                .endFill();
        }
    }

    renderUpdatingLabel() {
        const {
            viewportChanged,
            colorSchemeChanged,
            updateStatusChanged
        } = this.getSessionFlags();
        if (this.updatingLabel && (viewportChanged || colorSchemeChanged || updateStatusChanged)) {
            const anchor = 0.5;
            this.updatingLabel.anchor.set(anchor);
            this.updatingLabel.x = this.viewport.deviceWidth / 2.0;
            this.updatingLabel.y = this.viewport.deviceHeight / 2.0;
            this.updatingLabel.visible = this.updating;
        }
    }

    renderBlocks() {
        const updating = this.updating;
        const updateStatusChanged = this.session.updating !== updating;
        let blocksChanged = false;
        this.blocks.forEach(block => {
            block.container.alpha = updating ? config.updating.alpha : 1;
            blocksChanged = block.render() || blocksChanged;
        });
        return blocksChanged || updateStatusChanged;
    }

    renderHovered() {
        const hoveredChanged = this.hoveredChanged;
        this.hoveredRenderer.hovered = this.hovered;
        this.hoveredChanged = false;
        return this.hoveredRenderer.render() || hoveredChanged;
    }

    render() {
        if (
            !this.initialized ||
            this.viewport.invalid ||
            !this.data.dataReady ||
            !this.colorScheme.initialized
        ) {
            return false;
        }
        const colorFormat = this.colorScheme.colorFormat;
        this.colorScheme.colorFormat = ColorFormats.number;
        const {changed} = this.getSessionFlags();
        this.renderMask();
        this.renderBackground();
        const blocksChanged = this.renderBlocks();
        this.renderUpdatingLabel();
        const hoveredChanged = this.renderHovered();
        this.updateSessionFlags();
        this.colorScheme.colorFormat = colorFormat;
        return changed || blocksChanged || hoveredChanged;
    }
}

export default HeatmapDataRenderer;
