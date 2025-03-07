# NGB documentation

NGB documentation is provided in a **Markdown** format, that could be viewed directly from GitHub or could be built into **html** representation.

## Documentation index

The following sections are currently covered in a documentation

* Installation guide
    * [Overview](md/installation/overview.md)
    * [Using Docker](md/installation/docker.md)
    * [Using Binaries](md/installation/binaries.md)
    * [Using Standalone Jar](md/installation/standalone.md)
* Command Line Interface guide
    * [Overview](md/cli/introduction.md)
    * [CLI Installation](md/cli/installation.md)
    * [Running a command](md/cli/running-command.md)
    * [Accomplishing typical tasks](md/cli/typical-tasks.md)
    * [Command reference](md/cli/command-reference.md)
* User Interface Guide
    * [Overview](md/user-guide/overview.md)
    * [Datasets](md/user-guide/datasets.md)
    * Tracks/file formats
        * [Overview](md/user-guide/tracks.md)
        * [Reference track](md/user-guide/tracks-reference.md)
        * [Genes track](md/user-guide/tracks-genes.md)
        * [Alignments track](md/user-guide/tracks-bam.md)
        * [VCF track](md/user-guide/tracks-vcf.md)
        * [WIG track](md/user-guide/tracks-wig.md)
        * [BED track](md/user-guide/tracks-bed.md)
        * [FeatureCounts track](md/user-guide/tracks-featurecounts.md)
        * [Heatmap track](md/user-guide/tracks-heatmap.md)
        * [ENCODE-specific tracks](md/user-guide/tracks-encode.md)
    * [Variants](md/user-guide/variants.md)
    * [Genes](md/user-guide/genes.md)
    * [BLAST search](md/user-guide/blast-search.md)
    * [Homologs search](md/user-guide/homologs-search.md)
    * [Motifs search](md/user-guide/motifs-search.md)
    * [Strain lineage](md/user-guide/strain-lineage.md)
* Administrator guide
    * [Overview](md/user-guide/um-overview.md)
    * [Users](md/user-guide/um-users.md)
    * [Groups](md/user-guide/um-groups.md)
    * [Roles](md/user-guide/um-roles.md)
    * [Permissions management](md/user-guide/um-permissions.md)
* Developer guide
    * [Embedding NGB with URL](md/user-guide/embedding-url.md)
    * [Embedding NGB with JS](md/user-guide/embedding-js.md)

## Building documentation

[MkDocs](http://www.mkdocs.org) are used to build documentation into **html** representation.

So make sure that all dependencies are installed according to the [installation guide](https://www.mkdocs.org/user-guide/installation/).

Once installed, obtain **Markdown** sources from GitHub:

``` bash
$ git clone https://github.com/epam/NGB.git
$ cd NGB/docs
```

Run build:

``` bash
$ mkdocs build
```

This will create `site/` folder, containing built **html** documentation.  
To view documentation - navigate in `ngb/docs/site/` folder and launch `index.html` with a web-browser.
