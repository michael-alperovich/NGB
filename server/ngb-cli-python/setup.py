from setuptools import setup, find_packages
from src.version import __version__

with open('requirements.txt') as requirements_file:
    requirements = requirements_file.read().splitlines()

setup(
    name='NGB-CLI',
    version=__version__,
    py_modules=['pipe'],
    packages=find_packages(),
    include_package_data=True,
    install_requires=requirements,
    entry_points={
        'console_scripts': [
            'ngb-cli = src.ngb_cli:cli',
        ],
    },
)
