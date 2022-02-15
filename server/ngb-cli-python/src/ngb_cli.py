import click


@click.group()
def cli():
    """
    NGB CLI
    """


@cli.command()
@click.option('-n', '--name', help='your name')
def hello_world(name):
    """
    Greets User
    """
    if name:
        click.echo(f'Hello, {name}!')
    else:
        click.echo('Hello, world!')
