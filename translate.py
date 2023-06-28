sample_string = '''
(a, thin air): [r(0,0,1,1) r(1,0,1,1)] 
(b, aborted read): [w(0,0,1,1) w(1,0,1,1)] [r(0,0,2,1)] 
(c, future read): [r(0,0,1,1) w(0,0,1,1)] 
(d, not my own write): [w(0,0,1,1)] [w(0,1,2,1) r(0,0,2,1)] 
(e, intermediate read): [w(0,0,1,1) w(0,1,1,1)] [r(0,0,2,1)] 
(f, nonrepeatable read): [w(0,0,1,1)] [w(0,1,2,1)] [r(0,0,3,1) r(0,1,3,1)] 
(g, cyclicCO): [r(0,0,1,1) w(1,0,1,1)] [r(1,0,2,1)] [r(0,0,2,2)] 
(h, frac read CO): [w(0,1,1,1)] [r(0,1,2,1)] [w(0,0,2,2) w(1,0,2,2)] [r(0,1,3,1) r(1,0,3,1)] 
(i, frac read VO): [w(0,1,1,1)] [r(0,1,2,1)] [w(0,0,2,2) w(1,0,2,2)] [r(0,1,3,1) r(1,0,3,1)] [r(0,1,4,1)] [r(0,0,4,2)] 
(j, init read): [w(0,0,1,1)] [w(0,1,1,2) w(1,1,1,2)] [r(0,0,1,3)] 
(k, co conflict vo): [w(0,1,1,1)] [r(0,1,2,1)] [w(0,0,2,2) w(1,0,2,2)] [r(0,1,3,1)] 
(l, conflict vo): [w(0,1,1,1)] [r(0,1,2,1)] [w(0,0,2,2) w(1,0,2,2)] [r(0,1,3,1)] [r(0,1,4,1)] [r(0,0,4,2)]
'''

def parse_scenario(scenario_string):
    scenario_parts = scenario_string.strip().split(':')
    label, description = scenario_parts[0].strip(), scenario_parts[1].strip()
    print("des", description.split)
    transactions = [txn.strip() for txn in description.split(' ')]
    parsed_transactions = []
    for txn in transactions:
        print(txn)
    for txn in transactions:
        ops = txn.split()
        parsed_ops = []

        for op in ops:
            # print(op)
            op_type, op_args = op[0], op[1:-1]
            k, v, session_id, txn_id = map(int, op_args.strip('()').split(','))
            parsed_ops.append((op_type, k, v, session_id, txn_id))

        parsed_transactions.append(parsed_ops)

    return label, parsed_transactions
# Split the input string into separate scenarios
scenarios = [s.strip() for s in sample_string.strip().split('\n') if s.strip()]

# Parse each scenario and store the results in a dictionary
parsed_scenarios = {}
for scenario in scenarios:
    label, transactions = parse_scenario(scenario)
    parsed_scenarios[label] = transactions

# Print the parsed scenarios
for label, transactions in parsed_scenarios.items():
    print(f"Phasing {label}")
    # print(transactions)
