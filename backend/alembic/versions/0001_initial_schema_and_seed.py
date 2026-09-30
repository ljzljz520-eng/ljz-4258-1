"""Initial milk receipt schema and seed data"""
from alembic import op

revision = "0001_initial"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto")
    op.execute("""
    CREATE TYPE user_role AS ENUM ('OPERATOR', 'LAB', 'SUPERVISOR', 'ADMIN');
    CREATE TYPE event_type AS ENUM ('LOAD', 'REFILL', 'UNLOAD', 'SAMPLE');
    CREATE TYPE event_status AS ENUM ('ACCEPTED', 'QUARANTINED', 'REJECTED');
    CREATE TYPE material_node_type AS ENUM ('FARM_BATCH', 'COMPARTMENT', 'TANK', 'SAMPLE');
    CREATE TYPE seal_status AS ENUM ('EXPECTED', 'APPLIED', 'BROKEN', 'CONFLICT');
    CREATE TYPE device_type AS ENUM ('TEMPERATURE', 'WEIGHT');
    CREATE TYPE sample_status AS ENUM ('REGISTERED', 'ACCEPTED', 'REJECTED', 'CORRECTED');
    CREATE TYPE lab_result_status AS ENUM ('PENDING', 'ACCEPT', 'REJECT');
    CREATE TYPE release_status AS ENUM ('PENDING', 'SIGNED', 'INVALIDATED');
    """)

    op.execute("""
    CREATE TABLE users (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      username TEXT NOT NULL UNIQUE,
      full_name TEXT NOT NULL,
      password_hash TEXT NOT NULL,
      role user_role NOT NULL,
      is_active BOOLEAN NOT NULL DEFAULT TRUE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    CREATE TABLE vehicles (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      plate TEXT NOT NULL UNIQUE,
      carrier TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    CREATE TABLE farm_batches (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      batch_code TEXT NOT NULL UNIQUE,
      farm_name TEXT NOT NULL,
      declared_qty NUMERIC(12,3) NOT NULL CHECK (declared_qty > 0),
      created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    CREATE TABLE event_log (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      event_id TEXT NOT NULL UNIQUE,
      type event_type NOT NULL,
      terminal_id TEXT NOT NULL,
      actor_user_id UUID NOT NULL REFERENCES users(id),
      payload JSONB NOT NULL DEFAULT '{}'::jsonb,
      status event_status NOT NULL DEFAULT 'ACCEPTED',
      conflict_reason TEXT,
      client_created_at TIMESTAMPTZ NOT NULL,
      processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
      created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );
    CREATE INDEX idx_event_log_type_status ON event_log(type,status);

    CREATE TABLE compartments (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      vehicle_id UUID NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE,
      label TEXT NOT NULL,
      capacity NUMERIC(12,3) NOT NULL CHECK (capacity > 0),
      current_node_id UUID,
      created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
      UNIQUE(vehicle_id,label)
    );

    CREATE TABLE material_nodes (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      type material_node_type NOT NULL,
      farm_batch_id UUID REFERENCES farm_batches(id),
      quantity NUMERIC(12,3) NOT NULL CHECK (quantity >= 0),
      created_event_id UUID REFERENCES event_log(id),
      created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
      CHECK (
        (type = 'FARM_BATCH' AND farm_batch_id IS NOT NULL) OR
        (type <> 'FARM_BATCH' AND farm_batch_id IS NULL)
      )
    );
    CREATE INDEX idx_material_nodes_type ON material_nodes(type);

    ALTER TABLE compartments
      ADD CONSTRAINT fk_compartment_current_node
      FOREIGN KEY(current_node_id) REFERENCES material_nodes(id);

    CREATE TABLE receiving_tanks (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      tank_code TEXT NOT NULL UNIQUE,
      name TEXT NOT NULL,
      capacity NUMERIC(12,3) NOT NULL CHECK (capacity > 0),
      current_node_id UUID REFERENCES material_nodes(id),
      lineage_version INTEGER NOT NULL DEFAULT 1 CHECK (lineage_version >= 1),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    CREATE TABLE material_edges (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      event_id UUID REFERENCES event_log(id),
      source_node_id UUID REFERENCES material_nodes(id),
      destination_node_id UUID NOT NULL REFERENCES material_nodes(id),
      edge_kind TEXT NOT NULL DEFAULT 'TRANSFER' CHECK (edge_kind IN ('TRANSFER','CONTINUATION')),
      quantity NUMERIC(12,3) NOT NULL CHECK (quantity > 0),
      created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );
    CREATE UNIQUE INDEX uq_material_edges_event_source_destination ON material_edges(
      COALESCE(event_id, '00000000-0000-0000-0000-000000000000'::uuid),
      COALESCE(source_node_id, '00000000-0000-0000-0000-000000000000'::uuid),
      destination_node_id);
    CREATE INDEX idx_edges_source ON material_edges(source_node_id);
    CREATE INDEX idx_edges_destination ON material_edges(destination_node_id);

    CREATE TABLE seals (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      compartment_id UUID NOT NULL REFERENCES compartments(id) ON DELETE CASCADE,
      seal_number TEXT NOT NULL UNIQUE,
      status seal_status NOT NULL DEFAULT 'EXPECTED',
      applied_event_id UUID REFERENCES event_log(id),
      broken_event_id UUID REFERENCES event_log(id),
      created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    CREATE TABLE device_readings (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      reading_id TEXT NOT NULL UNIQUE,
      terminal_id TEXT NOT NULL,
      device_type device_type NOT NULL,
      device_id TEXT NOT NULL,
      vehicle_plate TEXT,
      value NUMERIC(12,3) NOT NULL,
      unit TEXT NOT NULL,
      recorded_at TIMESTAMPTZ NOT NULL,
      raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
      replay_position BIGINT,
      ingested_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );
    CREATE INDEX idx_device_readings_vehicle_time ON device_readings(vehicle_plate,recorded_at);

    CREATE TABLE samples (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      sample_code TEXT NOT NULL UNIQUE,
      source_node_id UUID NOT NULL REFERENCES material_nodes(id),
      sample_node_id UUID REFERENCES material_nodes(id),
      register_event_id UUID REFERENCES event_log(id),
      farm_batch_id UUID REFERENCES farm_batches(id),
      quantity NUMERIC(12,3) NOT NULL DEFAULT 0 CHECK (quantity >= 0),
      status sample_status NOT NULL DEFAULT 'REGISTERED',
      current_version INTEGER NOT NULL DEFAULT 0,
      created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    CREATE TABLE lab_results (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      sample_id UUID NOT NULL REFERENCES samples(id) ON DELETE CASCADE,
      version INTEGER NOT NULL,
      result lab_result_status NOT NULL,
      analyst_user_id UUID NOT NULL REFERENCES users(id),
      result_event_id UUID REFERENCES event_log(id),
      components JSONB NOT NULL DEFAULT '{}'::jsonb,
      note TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
      UNIQUE(sample_id,version)
    );

    CREATE TABLE release_applications (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      application_no TEXT NOT NULL UNIQUE,
      tank_id UUID NOT NULL REFERENCES receiving_tanks(id),
      expected_node_id UUID NOT NULL REFERENCES material_nodes(id),
      expected_lineage_version INTEGER NOT NULL,
      status release_status NOT NULL DEFAULT 'PENDING',
      requested_by UUID NOT NULL REFERENCES users(id),
      signed_by UUID REFERENCES users(id),
      invalid_reason TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
      signed_at TIMESTAMPTZ
    );
    CREATE UNIQUE INDEX uq_release_pending_per_tank
      ON release_applications(tank_id) WHERE status='PENDING';
    CREATE INDEX idx_release_tank_status ON release_applications(tank_id,status);
    """)

    op.execute("""
    CREATE OR REPLACE FUNCTION assert_material_edge_valid() RETURNS trigger AS $$
    BEGIN
      IF EXISTS (
        WITH RECURSIVE downstream AS (
          SELECT NEW.destination_node_id AS node_id
          UNION
          SELECT e.destination_node_id
          FROM material_edges e JOIN downstream d ON e.source_node_id = d.node_id
          WHERE e.edge_kind = 'TRANSFER'
        )
        SELECT 1 FROM downstream WHERE node_id = NEW.source_node_id
      ) THEN
        RAISE EXCEPTION 'refusing cyclic material edge % -> %', NEW.source_node_id, NEW.destination_node_id;
      END IF;

      IF NEW.source_node_id IS NOT NULL AND EXISTS (
        SELECT 1
        FROM material_nodes src
        WHERE src.id = NEW.source_node_id
          AND NEW.edge_kind = 'TRANSFER'
          AND COALESCE((SELECT SUM(quantity) FROM material_edges
                         WHERE source_node_id=src.id AND edge_kind='TRANSFER'
                           AND id <> COALESCE(NEW.id, '00000000-0000-0000-0000-000000000000'::uuid)),0)
              + CASE WHEN NEW.edge_kind='TRANSFER' THEN NEW.quantity ELSE 0 END
              > src.quantity
      ) THEN
        RAISE EXCEPTION 'material overdraw for node %', NEW.source_node_id;
      END IF;
      RETURN NEW;
    END;
    $$ LANGUAGE plpgsql;

    CREATE TRIGGER trg_material_edge_valid
    BEFORE INSERT ON material_edges
    FOR EACH ROW EXECUTE FUNCTION assert_material_edge_valid();

    CREATE OR REPLACE FUNCTION freeze_material_provenance() RETURNS trigger AS $$
    BEGIN
      RAISE EXCEPTION 'material provenance records are append-only';
    END;
    $$ LANGUAGE plpgsql;

    CREATE TRIGGER trg_freeze_material_edges
    BEFORE UPDATE OR DELETE ON material_edges
    FOR EACH ROW EXECUTE FUNCTION freeze_material_provenance();

    CREATE TRIGGER trg_freeze_material_nodes
    BEFORE UPDATE OR DELETE ON material_nodes
    FOR EACH ROW EXECUTE FUNCTION freeze_material_provenance();
    """)

    # Deterministic seed data. Passwords are documented in README.
    op.execute("""
    INSERT INTO users (id, username, full_name, password_hash, role) VALUES
      ('11111111-1111-1111-1111-111111111111','operator','场站操作员','pbkdf2_sha256$240000$yICrXGS3a59PJCghU/XMLw==$SfhxedXGL9p0oVgdDJF/7BvJ1FVsloIyfsq9OAZGlok=','OPERATOR'),
      ('22222222-2222-2222-2222-222222222222','lab','实验室检验员','pbkdf2_sha256$240000$uOvG/3aNjRoQdX8yexcvFA==$qFljwjGfZ506jbLxhizo+hkoVAnliuwIBVmGLuMoI2A=','LAB'),
      ('33333333-3333-3333-3333-333333333333','supervisor','接收主管','pbkdf2_sha256$240000$YYTueu+cddGtGTeESpRZPw==$Jv84XorzLPfP6D5OF778Lh+NkWD9ujlMKeEI4nM/TCc=','SUPERVISOR');

    INSERT INTO vehicles (id, plate, carrier) VALUES
      ('44444444-4444-4444-4444-444444444444','V-101','晨光牧业承运');

    INSERT INTO farm_batches (id, batch_code, farm_name, declared_qty) VALUES
      ('aaaaaaaa-0000-0000-0000-000000000001','FB-ALPHA','一牧场',600),
      ('aaaaaaaa-0000-0000-0000-000000000002','FB-BETA','二牧场',400),
      ('aaaaaaaa-0000-0000-0000-000000000003','FB-GAMMA','三牧场',800),
      ('aaaaaaaa-0000-0000-0000-000000000004','FB-DELTA','四牧场',300);

    INSERT INTO event_log (id,event_id,type,terminal_id,actor_user_id,payload,status,client_created_at)
    VALUES ('55555555-5555-5555-5555-555555555555','seed-load-001','LOAD','seed',
      '11111111-1111-1111-1111-111111111111','{"note":"migration seed"}','ACCEPTED',now());

    INSERT INTO compartments (id,vehicle_id,label,capacity,current_node_id) VALUES
      ('66666666-6666-6666-6666-666666666661','44444444-4444-4444-4444-444444444444','C1',1200,NULL),
      ('66666666-6666-6666-6666-666666666662','44444444-4444-4444-4444-444444444444','C2',1000,NULL);

    INSERT INTO material_nodes (id,type,farm_batch_id,quantity,created_event_id) VALUES
      ('77777777-7777-7777-7777-777777777001','FARM_BATCH','aaaaaaaa-0000-0000-0000-000000000001',600,'55555555-5555-5555-5555-555555555555'),
      ('77777777-7777-7777-7777-777777777002','FARM_BATCH','aaaaaaaa-0000-0000-0000-000000000002',400,'55555555-5555-5555-5555-555555555555'),
      ('77777777-7777-7777-7777-777777777003','FARM_BATCH','aaaaaaaa-0000-0000-0000-000000000003',800,'55555555-5555-5555-5555-555555555555'),
      ('77777777-7777-7777-7777-777777777004','FARM_BATCH','aaaaaaaa-0000-0000-0000-000000000004',300,'55555555-5555-5555-5555-555555555555'),
      ('77777777-7777-7777-7777-777777777701','COMPARTMENT',NULL,1000,'55555555-5555-5555-5555-555555555555'),
      ('77777777-7777-7777-7777-777777777702','COMPARTMENT',NULL,800,'55555555-5555-5555-5555-555555555555');

    UPDATE compartments SET current_node_id='77777777-7777-7777-7777-777777777701'
      WHERE id='66666666-6666-6666-6666-666666666661';
    UPDATE compartments SET current_node_id='77777777-7777-7777-7777-777777777702'
      WHERE id='66666666-6666-6666-6666-666666666662';

    INSERT INTO material_edges (event_id,source_node_id,destination_node_id,quantity) VALUES
      ('55555555-5555-5555-5555-555555555555','77777777-7777-7777-7777-777777777001','77777777-7777-7777-7777-777777777701',600),
      ('55555555-5555-5555-5555-555555555555','77777777-7777-7777-7777-777777777002','77777777-7777-7777-7777-777777777701',400),
      ('55555555-5555-5555-5555-555555555555','77777777-7777-7777-7777-777777777003','77777777-7777-7777-7777-777777777702',800);

    INSERT INTO seals (compartment_id,seal_number,status,applied_event_id) VALUES
      ('66666666-6666-6666-6666-666666666661','SEAL-C1-A','APPLIED','55555555-5555-5555-5555-555555555555'),
      ('66666666-6666-6666-6666-666666666662','SEAL-C2-A','APPLIED','55555555-5555-5555-5555-555555555555');

    INSERT INTO receiving_tanks (tank_code,name,capacity) VALUES
      ('T-A','一号接收罐',20000),
      ('T-B','二号接收罐',20000);
    """)


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS release_applications, lab_results, samples, device_readings, seals, material_edges, receiving_tanks, compartments, material_nodes, event_log, farm_batches, vehicles, users CASCADE")
    op.execute("""
    DROP TYPE IF EXISTS release_status, lab_result_status, sample_status, device_type, seal_status,
    material_node_type, event_status, event_type, user_role CASCADE
    """)
    op.execute("DROP FUNCTION IF EXISTS assert_material_edge_valid, freeze_material_provenance CASCADE")
