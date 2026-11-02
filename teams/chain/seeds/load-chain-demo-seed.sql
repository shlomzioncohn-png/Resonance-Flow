-- Small, readable dependency graph for the Bottlenecks screen: 18 tasks of one tenant, 20 dependencies.
-- Depends on Inbox's seed (teams/inbox/seeds/load-seeds.sql): the task ids and the tenant come from it.
-- Load that first, or the titles show as "Unknown task". Run: psql -U postgres -d chain -f load-chain-demo-seed.sql
\set ON_ERROR_STOP on

BEGIN;

-- tasks keep the status Inbox has, so a done task never depends on an open one
INSERT INTO task (id, tenant_id, tier, status) VALUES
    ('01242e36-4705-3966-9051-8e6fdcf39d91', '1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6', 'OPERATIONAL', 'DONE'),    -- d1
    ('01843aa5-2d19-310a-9c3e-676d71b0d268', '1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6', 'STRATEGIC',   'DONE'),    -- d2
    ('01d57509-8aa9-3170-9a0a-2776c9fec911', '1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6', 'OPERATIONAL', 'DONE'),    -- d3
    ('0187f621-809c-3c18-8953-13a542edaa99', '1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6', 'OPERATIONAL', 'PENDING'), -- hub A
    ('013b70da-a8e3-39fd-a8c2-7524f90c96b1', '1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6', 'OPERATIONAL', 'ACTIVE'),  -- hub B
    ('06b45bbc-f880-33d2-8641-9a1ed3e812ad', '1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6', 'OPERATIONAL', 'ACTIVE'),  -- a2
    ('09f31da7-02f8-338b-8338-fb422d6e350c', '1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6', 'OPERATIONAL', 'ACTIVE'),  -- a3
    ('032e688e-1d4e-3c2e-affa-556b49bd551c', '1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6', 'OPERATIONAL', 'PENDING'), -- p2
    ('03ae5e4e-36dc-320d-89cb-bd467178ac13', '1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6', 'OPERATIONAL', 'PENDING'), -- p3
    ('05ca364f-5f23-3acc-920f-304c5245fcc5', '1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6', 'OPERATIONAL', 'PENDING'), -- p4
    ('071e4576-86f6-3662-ae82-0f74ad31d848', '1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6', 'OPERATIONAL', 'PENDING'), -- p5
    ('073ccc70-5460-3842-a4b4-7f441a507181', '1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6', 'OPERATIONAL', 'PENDING'), -- p6
    ('091e0542-e591-3397-99e0-c7d869f8108b', '1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6', 'OPERATIONAL', 'PENDING'), -- p7
    ('09350916-b9d4-39a5-9c37-6844af0a565d', '1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6', 'OPERATIONAL', 'PENDING'), -- p8
    ('0a1f393c-f782-3a48-abca-6fb5da88437f', '1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6', 'OPERATIONAL', 'PENDING'), -- p9
    ('0bf9a2ac-c46b-3bf7-b5e0-66b85114e1e2', '1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6', 'TACTICAL',    'PENDING'), -- p10
    ('0c79923a-363e-3ca5-bd78-5d0afed30aa7', '1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6', 'TACTICAL',    'PENDING'), -- p11
    ('0ce0dc7c-5882-33a9-b15a-508d4b9417ff', '1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6', 'OPERATIONAL', 'PENDING')  -- p12
ON CONFLICT (id) DO NOTHING;

-- (task, depends on, confidence): hub A blocks 6, hub B blocks 5 (8 with the p8 -> p10 -> p11 -> p12 chain), no cycles
INSERT INTO task_dependency (task_id, depends_on_task_id, confidence) VALUES
    ('0187f621-809c-3c18-8953-13a542edaa99', '01242e36-4705-3966-9051-8e6fdcf39d91', 0.95), -- hub A after d1
    ('0187f621-809c-3c18-8953-13a542edaa99', '01843aa5-2d19-310a-9c3e-676d71b0d268', 0.90), -- hub A after d2
    ('013b70da-a8e3-39fd-a8c2-7524f90c96b1', '01d57509-8aa9-3170-9a0a-2776c9fec911', 0.92), -- hub B after d3
    ('06b45bbc-f880-33d2-8641-9a1ed3e812ad', '01d57509-8aa9-3170-9a0a-2776c9fec911', 0.85), -- a2 after d3
    ('09f31da7-02f8-338b-8338-fb422d6e350c', '01242e36-4705-3966-9051-8e6fdcf39d91', 0.88), -- a3 after d1
    ('09f31da7-02f8-338b-8338-fb422d6e350c', '01843aa5-2d19-310a-9c3e-676d71b0d268', 0.80), -- a3 after d2
    ('032e688e-1d4e-3c2e-affa-556b49bd551c', '0187f621-809c-3c18-8953-13a542edaa99', 0.93), -- p2 after hub A
    ('03ae5e4e-36dc-320d-89cb-bd467178ac13', '0187f621-809c-3c18-8953-13a542edaa99', 0.87), -- p3 after hub A
    ('05ca364f-5f23-3acc-920f-304c5245fcc5', '0187f621-809c-3c18-8953-13a542edaa99', 0.78), -- p4 after hub A
    ('071e4576-86f6-3662-ae82-0f74ad31d848', '0187f621-809c-3c18-8953-13a542edaa99', 0.91), -- p5 after hub A
    ('071e4576-86f6-3662-ae82-0f74ad31d848', '013b70da-a8e3-39fd-a8c2-7524f90c96b1', 0.82), -- p5 after hub B
    ('073ccc70-5460-3842-a4b4-7f441a507181', '0187f621-809c-3c18-8953-13a542edaa99', 0.84), -- p6 after hub A
    ('073ccc70-5460-3842-a4b4-7f441a507181', '013b70da-a8e3-39fd-a8c2-7524f90c96b1', 0.76), -- p6 after hub B
    ('091e0542-e591-3397-99e0-c7d869f8108b', '0187f621-809c-3c18-8953-13a542edaa99', 0.89), -- p7 after hub A
    ('091e0542-e591-3397-99e0-c7d869f8108b', '013b70da-a8e3-39fd-a8c2-7524f90c96b1', 0.70), -- p7 after hub B
    ('09350916-b9d4-39a5-9c37-6844af0a565d', '013b70da-a8e3-39fd-a8c2-7524f90c96b1', 0.94), -- p8 after hub B
    ('0a1f393c-f782-3a48-abca-6fb5da88437f', '013b70da-a8e3-39fd-a8c2-7524f90c96b1', 0.86), -- p9 after hub B
    ('0bf9a2ac-c46b-3bf7-b5e0-66b85114e1e2', '09350916-b9d4-39a5-9c37-6844af0a565d', 0.90), -- p10 after p8
    ('0c79923a-363e-3ca5-bd78-5d0afed30aa7', '0bf9a2ac-c46b-3bf7-b5e0-66b85114e1e2', 0.83), -- p11 after p10
    ('0ce0dc7c-5882-33a9-b15a-508d4b9417ff', '0c79923a-363e-3ca5-bd78-5d0afed30aa7', 0.79)  -- p12 after p11
ON CONFLICT DO NOTHING;

COMMIT;
