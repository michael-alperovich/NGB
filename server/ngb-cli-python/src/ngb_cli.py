import click
from .api.base import API
from .api.reference import ReferenceAPI


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


@cli.command()
def api_version():
    """
    Returns API version
    """
    api = API.instance()
    response_data = api.call("version", data=None)
    click.echo(f'API version: {response_data["payload"]}')


@cli.group()
def reference():
    """
    Reference related commands
    """


@reference.command(name='list')
def reference_list():
    response_data = ReferenceAPI.list_references()
    click.echo(response_data)