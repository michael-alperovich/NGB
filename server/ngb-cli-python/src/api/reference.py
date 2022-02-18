from .base import API
from src.utils import format_output_table


class ReferenceAPI(API):

    @classmethod
    def list_references(cls):
        api = cls.instance()
        response_data = api.call('reference/loadAll', data=None)
        if not response_data.get('payload'):
            return None
        else:
            return format_output_table(response_data['payload'],
                                       ['id', 'name', 'bioDataItemId', 'type', 'path', 'source', 'format',
                                        'createdDate'])
