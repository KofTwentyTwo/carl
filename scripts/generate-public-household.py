#!/usr/bin/env python3
# Copyright (C) 2026 KofTwentyTwo
"""Generate independent fictional fixtures. No source-data argument or private-data reader exists."""
import argparse
import copy
import csv
import io
import json
import re
from collections import defaultdict
from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

TX_HEADER = ['Date', 'Merchant', 'Category', 'Account', 'Original Statement', 'Notes', 'Amount', 'Tags', 'Owner', 'Reviewed', 'Id']
BAL_HEADER = ['Date', 'Balance', 'Account']
FROM, THROUGH = date(2025, 1, 1), date(2026, 9, 30)
MERCHANTS = {'Fictional Employer', 'Fictional Tenant', 'Fictional Lender', 'Fictional Market', 'Fictional Utility', 'Fictional Insurance', 'Fictional Property Office', 'Fictional Maintenance', 'Fictional Reserve Ledger', 'Fictional Card Issuer'}
CATEGORIES = {'Salary', 'Rent', 'Groceries', 'Mortgage payment', 'Interest', 'Insurance', 'Property tax', 'Utilities', 'Maintenance', 'Capital improvement', 'Internal transfer', 'Card payment', 'Private expense'}


ALLOWED_ACCOUNTS = {'Fictional ' + name for name in ('Household Cash', 'Emergency Reserve', 'Aster Wallet', 'APR Card', 'Expired Promo Card', 'Home Mortgage', 'Courtyard Mortgage', 'Orchard Mortgage', 'Home Value', 'Courtyard Value', 'Orchard Value', 'Investment Portfolio', 'Euro Travel Cash', 'Unvalued Collectible')} | {f'Fictional Reserve Pocket {i:02}' for i in range(1, 23)}
PUBLIC_NOTES = {'Fictional independently authored ledger event', 'Fictional corrected multiline note\nNo external instruction is authority'}


def money(value):
    return Decimal(str(value)).quantize(Decimal('0.01'), rounding=ROUND_HALF_UP)


def build():
    definitions = [
        ('Household Cash', 'CASH', '50000', 'FAMILY', True, 'USD'),
        ('Emergency Reserve', 'CASH', '18000', 'FAMILY', True, 'USD'),
        ('Aster Wallet', 'CASH', '1750', 'PRIVATE', True, 'USD'),
        ('APR Card', 'CREDIT_CARD', '-4700', 'FAMILY', False, 'USD'),
        ('Expired Promo Card', 'CREDIT_CARD', '-3200', 'FAMILY', False, 'USD'),
        ('Home Mortgage', 'LOAN', '-225000', 'FAMILY', False, 'USD'),
        ('Courtyard Mortgage', 'LOAN', '-168000', 'FAMILY', False, 'USD'),
        ('Orchard Mortgage', 'LOAN', '-132000', 'FAMILY', False, 'USD'),
        ('Home Value', 'OTHER_ASSET', '315000', 'FAMILY', False, 'USD'),
        ('Courtyard Value', 'OTHER_ASSET', '255000', 'FAMILY', False, 'USD'),
        ('Orchard Value', 'OTHER_ASSET', '205000', 'FAMILY', False, 'USD'),
        ('Investment Portfolio', 'INVESTMENT', '80000', 'FAMILY', False, 'USD'),
        ('Euro Travel Cash', 'CASH', '1800', 'FAMILY', True, 'EUR'),
        ('Unvalued Collectible', 'OTHER_ASSET', None, 'PRIVATE', False, 'USD'),
    ] + [(f'Reserve Pocket {i:02}', 'CASH', '100', 'FAMILY', True, 'USD') for i in range(1, 23)]
    accounts = [{'label': 'Fictional ' + n, 'kind': k, 'opening': None if v is None else str(money(v)), 'visibility': vis, 'liquid': liquid, 'currency': currency, 'ownershipShare': '1.0', 'evidence': 'Fictional independently authored opening observation'} for n, k, v, vis, liquid, currency in definitions]
    ledger = {a['label']: money(a['opening']) for a in accounts if a['opening'] is not None}
    tx, balances, metadata = [], [], {}
    cash = 'Fictional Household Cash'
    def add(day, account, amount, merchant, category, classification, tag=''):
        amount = money(amount)
        identifier = f'fictional-tx-{len(tx) + 1:06}'
        tx.append(dict(zip(TX_HEADER, [str(day), merchant, category, account, 'Fictional supplied statement', 'Fictional independently authored ledger event', str(amount), tag, 'Fictional Aster', 'true', identifier])))
        metadata[identifier] = {'classification': classification, 'category': category}
        ledger[account] += amount
    def pair(day, outgoing, incoming, amount, category, name):
        tag = f'pair:{name}:{day}'
        add(day, outgoing, -money(amount), 'Fictional Reserve Ledger', category, 'TRANSFER', tag)
        add(day, incoming, amount, 'Fictional Reserve Ledger', category, 'TRANSFER', tag)
    def observe(day):
        balances.extend({'Date': str(day), 'Balance': str(value), 'Account': label} for label, value in ledger.items())
    observe(FROM - timedelta(days=1))
    day = FROM
    loans = [('Fictional Home Mortgage', '1500', '0.0425'), ('Fictional Courtyard Mortgage', '1300', '0.0525'), ('Fictional Orchard Mortgage', '1100', '0.0475')]
    while day <= THROUGH:
        for i in range(1, 5):
            pocket = f'Fictional Reserve Pocket {i:02}'
            pair(day, cash, pocket, '1.37', 'Internal transfer', f'pocket-in-{i}')
            pair(day, pocket, cash, '1.37', 'Internal transfer', f'pocket-out-{i}')
        if day.day == 1:
            add(day, cash, '6200', 'Fictional Employer', 'Salary', 'INCOME')
        if day.day == 5:
            for unit, rent in [('courtyard-1', '1250'), ('courtyard-2', '900'), ('orchard-1', '1475')]:
                if unit == 'orchard-1' and day >= date(2026, 7, 1):
                    continue
                if unit == 'courtyard-2' and day == date(2026, 9, 5):
                    rent = '650'
                add(day, cash, rent, 'Fictional Tenant', 'Rent', 'INCOME', 'rent:' + unit)
        if day.day == 9:
            for loan, payment, apr in loans:
                add(day, loan, -abs(ledger[loan]) * Decimal(apr) / 12, 'Fictional Lender', 'Interest', 'DEBT_INTEREST')
            for card, apr in [('Fictional APR Card', '0.2399'), ('Fictional Expired Promo Card', '0' if day.year == 2025 else '0.2099')]:
                add(day, card, -abs(ledger[card]) * Decimal(apr) / 12, 'Fictional Card Issuer', 'Interest', 'DEBT_INTEREST')
        if day.day == 10:
            for loan, payment, apr in loans:
                pair(day, cash, loan, payment, 'Mortgage payment', loan.replace('Fictional ', '').replace(' ', '-').lower())
            pair(day, cash, 'Fictional APR Card', '450', 'Card payment', 'apr-card')
            pair(day, cash, 'Fictional Expired Promo Card', '100', 'Card payment', 'promo-card')
        if day.day == 14:
            add(day, 'Fictional APR Card', '-650', 'Fictional Market', 'Groceries', 'EXPENSE')
            add(day, 'Fictional Aster Wallet', '-23.75', 'Fictional Market', 'Private expense', 'EXPENSE')
        if day.day == 18:
            add(day, cash, '-190', 'Fictional Utility', 'Utilities', 'EXPENSE')
            for prop in ('home', 'courtyard', 'orchard'):
                for category, value, merchant in [('Property tax', '-250', 'Fictional Property Office'), ('Insurance', '-110', 'Fictional Insurance'), ('Utilities', '-120', 'Fictional Utility'), ('Maintenance', '-85', 'Fictional Maintenance')]:
                    add(day, cash, value, merchant, category, 'EXPENSE', 'property:' + prop)
        if day == date(2026, 4, 20):
            add(day, cash, '-2200', 'Fictional Maintenance', 'Capital improvement', 'CAPITAL', 'property:courtyard')
        observe(day)
        day += timedelta(days=1)
    expected = {
        'transactionCount': len(tx), 'balanceCount': len(balances),
        'closingByAccount': {k: str(v) for k, v in ledger.items()},
        'liquidSharedUSD': str(sum((ledger[a['label']] for a in accounts if a['liquid'] and a['visibility'] == 'FAMILY' and a['currency'] == 'USD'), Decimal(0))),
        'availableCreditUSD': str(money('60000') + ledger['Fictional APR Card']),
        'excludedSharedAccounts': [a['label'] for a in accounts if a['visibility'] != 'FAMILY'],
        'sharedAccountLabels': [a['label'] for a in accounts if a['visibility'] == 'FAMILY'],
        'unpaidSeptemberRentUSD': '250.00',
        'missingValuationLabels': ['Fictional Unvalued Collectible'],
    }
    september_flows = defaultdict(Decimal)
    for row in tx:
        if row['Date'].startswith('2026-09') and next(a for a in accounts if a['label'] == row['Account'])['visibility'] == 'FAMILY':
            classification = metadata[row['Id']]['classification']
            if classification != 'TRANSFER':
                september_flows['USD:' + classification] += Decimal(row['Amount'])
    expected['sharedSeptemberFlows'] = {key: str(value) for key, value in sorted(september_flows.items())}
    properties = [
        {'key': 'home', 'title': 'Fictional Lantern Primary Home', 'use': 'PRIMARY_RESIDENCE', 'asset': 'Fictional Home Value', 'debt': 'Fictional Home Mortgage', 'marketValue': '315000.00', 'costBasis': '280000.00', 'landBasis': '70000.00', 'buildingBasis': '210000.00', 'mortgagePayment': '1500.00', 'apr': '0.0425', 'maturity': '2042-12-31', 'units': []},
        {'key': 'courtyard', 'title': 'Fictional Courtyard Duplex', 'use': 'RENTAL', 'asset': 'Fictional Courtyard Value', 'debt': 'Fictional Courtyard Mortgage', 'marketValue': '255000.00', 'costBasis': '210000.00', 'landBasis': '50000.00', 'buildingBasis': '160000.00', 'mortgagePayment': '1300.00', 'apr': '0.0525', 'maturity': '2044-06-30', 'units': [{'key': 'courtyard-1', 'label': 'Fictional Courtyard Unit One', 'rent': '1250.00', 'occupancy': 'OCCUPIED', 'leaseStart': '2025-01-01', 'leaseEnd': '2026-12-31'}, {'key': 'courtyard-2', 'label': 'Fictional Courtyard Unit Two', 'rent': '900.00', 'occupancy': 'OCCUPIED', 'leaseStart': '2025-01-01', 'leaseEnd': '2026-12-31'}]},
        {'key': 'orchard', 'title': 'Fictional Orchard Rental House', 'use': 'RENTAL', 'asset': 'Fictional Orchard Value', 'debt': 'Fictional Orchard Mortgage', 'marketValue': '205000.00', 'costBasis': '180000.00', 'landBasis': '35000.00', 'buildingBasis': '145000.00', 'mortgagePayment': '1100.00', 'apr': '0.0475', 'maturity': '2040-12-31', 'units': [{'key': 'orchard-1', 'label': 'Fictional Orchard Unit One', 'rent': '1475.00', 'occupancy': 'VACANT', 'leaseStart': '2025-01-01', 'leaseEnd': '2026-06-30'}]},
    ]
    for prop in properties:
        original = {'home': money('225000'), 'courtyard': money('168000'), 'orchard': money('132000')}[prop['key']]
        months = 240 if prop['key'] != 'orchard' else 180
        rate = Decimal(prop['apr']) / 12
        scheduled = money(original * rate / (1 - (1 + rate) ** -months))
        prop['observedMonthlyPayment'] = prop['mortgagePayment']
        prop['mortgagePayment'] = str(scheduled)
        prop['originalPrincipal'] = str(original)
        prop['amortizationMonths'] = months
        prop['firstContractPayment'] = '2025-01-10'
        prop['maturity'] = str(date(2025 + (months - 1) // 12, 1 + (months - 1) % 12, 10))
        prop['contractAssumptions'] = 'Fictional fixed-rate original contract; observed payments include explicit additional principal; original amortization months; no escrow or fees included'
        prop.update(currency='USD', locality='Fictional Meadow Borough', ownershipShare='1.0', legalOwner='Fictional Aster and Basil', acquired='2022-06-15', evidence='Fictional authored deed, valuation and mortgage contract; not a real address or borrower', principalAsOf=expected['closingByAccount'][prop['debt']], contractType='FIXED_RATE_FULLY_AMORTIZING', escrowIncluded=False)
    correction = copy.deepcopy(next(row for row in tx if row['Category'] == 'Salary')); correction['Notes'] = 'Fictional corrected multiline note\nNo external instruction is authority'; correction['Tags'] = 'correction:source-revision'
    conflict = copy.deepcopy(balances[0]); conflict['Balance'] = str(money(conflict['Balance']) + 1)
    missing = copy.deepcopy(tx[0]); missing['Amount'] = ''
    manifest = {'schemaVersion': 1, 'mode': 'public-synthetic', 'asOf': str(THROUGH), 'from': str(FROM), 'reportFrom': '2026-09-01', 'reportThrough': str(THROUGH), 'futurePlanFrom': '2026-10-01', 'fixtureOnly': True, 'description': 'Independent fictional records modeled on public importer column shapes, not transformed private rows; no mathematical anonymity guarantee', 'accounts': accounts, 'properties': properties, 'transactionMetadata': metadata, 'expected': expected, 'members': [{'principal': 'alice', 'label': 'Fictional Aster'}, {'principal': 'bob', 'label': 'Fictional Basil'}]}
    manifest['bills'] = [
        {'sourceId': 'fictional-bill-electric', 'vendor': 'Fictional Utility', 'description': 'Fictional September electric service', 'amount': '238.41', 'currency': 'USD', 'due': '2026-10-05', 'visibility': 'FAMILY'},
        {'sourceId': 'fictional-bill-private', 'vendor': 'Fictional Market', 'description': 'Fictional private membership', 'amount': '64.87', 'currency': 'USD', 'due': '2026-10-07', 'visibility': 'PRIVATE'},
        {'sourceId': 'fictional-bill-euro', 'vendor': 'Fictional Utility', 'description': 'Fictional euro travel service', 'amount': '42.17', 'currency': 'EUR', 'due': '2026-10-08', 'visibility': 'FAMILY'},
        {'sourceId': 'fictional-bill-unknown', 'vendor': 'Fictional Maintenance', 'description': 'Fictional quote pending amount and date', 'amount': None, 'currency': 'USD', 'due': None, 'visibility': 'FAMILY'},
    ]
    manifest['debtAssumptions'] = {'aprCardRate': '0.2399', 'expiredPromoRate': '0.2099', 'aprCardCreditLimit': '60000.00', 'creditLimitation': 'Available credit is borrowing capacity, never cash; fictional promotion expired 2025-12-31; no transfer or application executed', 'monthlyAllocation': '6500.00', 'horizonMonths': 240}
    manifest['cashAssumptions'] = {'currency': 'USD', 'reserve': '20000.00', 'discretionaryCap': '350.00', 'obligationsCovered': False, 'scopeComplete': False, 'incomeSupported': True, 'limitation': 'Unknown future quote and private inputs excluded; conditional selected-input budget, no household completeness attestation'}
    manifest['calendar'] = {'eventFile': 'calendar.ics', 'reminderFile': 'reminders.ics', 'limitation': 'Fictional offline observations; root fixture must serve and ingest through native CalDAV connector, never a production collection'}
    return {'manifest': manifest, 'transactions': tx, 'balances': balances, 'cases': {'overlap': copy.deepcopy(tx[:24]), 'correction': [correction], 'balanceConflict': [copy.deepcopy(balances[0]), conflict], 'missingAmount': [missing], 'omitted': copy.deepcopy(tx[1:24])}}


def validate(dataset):
    manifest, transactions = dataset['manifest'], dataset['transactions']
    accounts = {a['label']: a for a in manifest['accounts']}
    if set(accounts) != ALLOWED_ACCOUNTS:
        raise ValueError('Public account allowlist violation')
    for row in transactions:
        if row['Reviewed'] != 'true' or not re.fullmatch(r'(pair:[a-z0-9-]+:202[56]-[0-9]{2}-[0-9]{2}|rent:(courtyard-[12]|orchard-1)|property:(home|courtyard|orchard)|)', row['Tags']):
            raise ValueError('Public tag allowlist violation')
        if row['Notes'] not in PUBLIC_NOTES or row['Original Statement'] != 'Fictional supplied statement' or not re.fullmatch(r'fictional-tx-[0-9]{6}', row['Id']):
            raise ValueError('Public text allowlist violation')
        if row['Merchant'] not in MERCHANTS or row['Account'] not in accounts or row['Category'] not in CATEGORIES or row['Owner'] != 'Fictional Aster':
            raise ValueError('Public allowlist violation')
    pairs = defaultdict(list)
    for row in transactions:
        if row['Tags'].startswith('pair:'):
            pairs[row['Tags']].append(money(row['Amount']))
    if any(len(values) != 2 or sum(values) != 0 for values in pairs.values()):
        raise ValueError('Unbalanced transfer pair')
    ledger = {a['label']: money(a['opening']) for a in accounts.values() if a['opening'] is not None}
    daily = defaultdict(list)
    for row in transactions:
        daily[row['Date']].append(row)
    observations = defaultdict(list)
    for row in dataset['balances']:
        observations[row['Date']].append(row)
    for day in sorted(observations):
        for row in daily[day]:
            ledger[row['Account']] += money(row['Amount'])
        if len(observations[day]) != len(ledger):
            raise ValueError('Missing daily balance')
        for row in observations[day]:
            if money(row['Balance']) != ledger[row['Account']]:
                raise ValueError('Daily balance does not reconcile')
    if any(ledger[a['label']] >= 0 for a in accounts.values() if a['kind'] in ('LOAN', 'CREDIT_CARD')):
        raise ValueError('Liability sign lost')
    if {k: str(v) for k, v in ledger.items()} != manifest['expected']['closingByAccount']:
        raise ValueError('Expected closing balance does not reconcile')
    if ledger['Fictional Household Cash'] <= 0:
        raise ValueError('Cash ledger exhausted')


def csv_text(header, rows):
    output = io.StringIO(newline='')
    writer = csv.DictWriter(output, header, lineterminator='\n')
    writer.writeheader(); writer.writerows(rows)
    return output.getvalue()


def serialize(dataset):
    outputs = {'household.json': json.dumps(dataset['manifest'], indent=2, sort_keys=True) + '\n', 'transactions.csv': csv_text(TX_HEADER, dataset['transactions']), 'balances.csv': csv_text(BAL_HEADER, dataset['balances'])}
    for name, rows in dataset['cases'].items():
        outputs['case-' + name + '.csv'] = csv_text(BAL_HEADER if name == 'balanceConflict' else TX_HEADER, rows)
    outputs['calendar.ics'] = 'BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Carl//Public Fictional Household//EN\r\nBEGIN:VEVENT\r\nUID:fictional-household-review\r\nDTSTAMP:20260930T000000Z\r\nDTSTART:20261002T150000Z\r\nDTEND:20261002T160000Z\r\nSUMMARY:Fictional shared household review\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n'
    outputs['reminders.ics'] = 'BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Carl//Public Fictional Household//EN\r\nBEGIN:VTODO\r\nUID:fictional-offline-quote-review\r\nDTSTAMP:20260930T000000Z\r\nDUE:20261005T150000Z\r\nSUMMARY:Fictional review maintenance quote\r\nSTATUS:NEEDS-ACTION\r\nEND:VTODO\r\nEND:VCALENDAR\r\n'
    return outputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    dataset = build(); validate(dataset)
    args.output.mkdir(parents=True, exist_ok=True)
    for name, contents in serialize(dataset).items():
        (args.output / name).write_text(contents, encoding='utf-8')
    print('Generated validated independent public synthetic fixtures')


if __name__ == '__main__':
    main()
