import json
import requests


class API(object):

    def __init__(self):
        self.__headers__ = {'Content-Type': 'application/json'}

        self.__attempts__ = 3
        self.__timeout__ = 5
        self.__connection_timeout__ = 10
        self.api = "http://localhost:8080/catgenome/restapi/"

    def get_headers(self):
        return self.__headers__

    def call(self, method, data, http_method=None, error_message=None):
        url = f'{self.api.strip("/")}/{method}'

        if http_method is None or method.lower() not in ['get', 'post', 'put', 'delete']:
            http_method = 'post' if data else 'get'
        else:
            http_method = http_method.lower()

        args = {'url': url, 'headers': self.__headers__, 'verify': False}
        if data:
            args['data'] = data

        response = getattr(requests, http_method)(**args)

        return self._build_response_data(response, error_message)

    @classmethod
    def _build_response_data(cls, response, error_message=None):
        response_data = json.loads(response.text)
        message_text = error_message if error_message else 'Failed to fetch data from server'
        if 'status' not in response_data:
            raise RuntimeError('{}. Server responded with status: {}.'
                               .format(message_text, str(response_data.status_code)))
        if response_data['status'] != 'OK':
            raise RuntimeError('{}. Server responded with message: {}'.format(message_text, response_data['message']))
        else:
            return response_data

    @classmethod
    def instance(cls):
        return cls()
