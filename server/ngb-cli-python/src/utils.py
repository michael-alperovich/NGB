from prettytable import PrettyTable


def format_output_table(data, headers):
    table = PrettyTable()
    table.field_names = headers

    for row in data:
        table_row = [row[header] for header in headers]
        table.add_row(table_row)

    return table
