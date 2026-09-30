"""initial receiving lineage schema

Revision ID: 0001_initial
Revises: 
Create Date: 2026-09-30
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "0001_initial"
down_revision = None
branch_labels = None
depends_on = None

JSONB = sa.JSON().with_variant(postgresql.JSONB(), "postgresql")


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("username", sa.String(64), nullable=False, unique=True),
        sa.Column("hashed_password", sa.String(255), nullable=False),
        sa.Column("role", sa.String(32), nullable=False),
        sa.Column("full_name", sa.String(128), nullable=False),
        sa.Column("is_active", sa.Boolean, nullable=False, server_default=sa.true()),
    )
    op.create_table("farms",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("code", sa.String(64), nullable=False, unique=True),
        sa.Column("name", sa.String(255), nullable=False),
    )
    op.create_table("pasture_batches",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("farm_id", sa.Integer, sa.ForeignKey("farms.id"), nullable=False),
        sa.Column("code", sa.String(64), nullable=False, unique=True),
        sa.Column("collected_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("declared_amount", sa.Numeric(14, 3), nullable=False),
    )
    op.create_table("vehicles",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("code", sa.String(64), nullable=False, unique=True),
        sa.Column("plate", sa.String(32), nullable=False, unique=True),
    )
    op.create_table("receiving_tanks",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("code", sa.String(64), nullable=False, unique=True),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("capacity", sa.Numeric(14, 3), nullable=False),
        sa.Column("current_node_id", sa.Integer, nullable=True),
        sa.Column("version", sa.Integer, nullable=False, server_default="1"),
    )
    op.create_table("compartments",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("vehicle_id", sa.Integer, sa.ForeignKey("vehicles.id"), nullable=False),
        sa.Column("code", sa.String(32), nullable=False),
        sa.Column("capacity", sa.Numeric(14, 3), nullable=False),
        sa.Column("current_node_id", sa.Integer, nullable=True),
        sa.Column("version", sa.Integer, nullable=False, server_default="1"),
        sa.UniqueConstraint("vehicle_id", "code", name="uq_compartment_vehicle_code"),
    )
    op.create_table("material_nodes",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("node_type", sa.String(32), nullable=False),
        sa.Column("pasture_batch_id", sa.Integer, sa.ForeignKey("pasture_batches.id"), nullable=True),
        sa.Column("compartment_id", sa.Integer, sa.ForeignKey("compartments.id"), nullable=True),
        sa.Column("tank_id", sa.Integer, sa.ForeignKey("receiving_tanks.id"), nullable=True),
        sa.Column("state_seq", sa.Integer, nullable=False, server_default="1"),
        sa.Column("remaining_amount", sa.Numeric(14, 3), nullable=False),
        sa.Column("version", sa.Integer, nullable=False, server_default="1"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.CheckConstraint("remaining_amount >= 0", name="ck_material_node_remaining_non_negative"),
        sa.CheckConstraint("node_type in ('pasture_batch','compartment','tank')", name="ck_material_node_type"),
    )
    op.create_foreign_key("fk_compartments_current_node", "compartments", "material_nodes",
                          ["current_node_id"], ["id"])
    op.create_foreign_key("fk_tanks_current_node", "receiving_tanks", "material_nodes",
                          ["current_node_id"], ["id"])
    op.create_table("arrivals",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("event_id", sa.String(80), nullable=False, unique=True),
        sa.Column("vehicle_id", sa.Integer, sa.ForeignKey("vehicles.id"), nullable=False),
        sa.Column("arrived_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("status", sa.String(32), nullable=False, server_default="registered"),
        sa.Column("created_by", sa.Integer, sa.ForeignKey("users.id"), nullable=False),
    )
    op.create_table("arrival_items",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("arrival_id", sa.Integer, sa.ForeignKey("arrivals.id"), nullable=False),
        sa.Column("compartment_id", sa.Integer, sa.ForeignKey("compartments.id"), nullable=False),
        sa.Column("pasture_batch_id", sa.Integer, sa.ForeignKey("pasture_batches.id"), nullable=False),
        sa.Column("declared_amount", sa.Numeric(14, 3), nullable=False),
        sa.Column("initial_node_id", sa.Integer, sa.ForeignKey("material_nodes.id"), nullable=True),
    )
    op.create_table("seals",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("arrival_item_id", sa.Integer, sa.ForeignKey("arrival_items.id"), nullable=False),
        sa.Column("code", sa.String(80), nullable=False, unique=True),
        sa.Column("state", sa.String(32), nullable=False, server_default="sealed"),
        sa.Column("registered_by", sa.Integer, sa.ForeignKey("users.id"), nullable=False),
    )
    op.create_table("material_flows",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("from_node_id", sa.Integer, sa.ForeignKey("material_nodes.id"), nullable=False),
        sa.Column("to_node_id", sa.Integer, sa.ForeignKey("material_nodes.id"), nullable=False),
        sa.Column("amount", sa.Numeric(14, 3), nullable=False),
        sa.Column("cause", sa.String(64), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.CheckConstraint("amount > 0", name="ck_material_flow_amount_positive"),
        sa.CheckConstraint("from_node_id <> to_node_id", name="ck_material_flow_no_self_loop"),
    )
    op.create_index("ix_material_flows_from", "material_flows", ["from_node_id"])
    op.create_index("ix_material_flows_to", "material_flows", ["to_node_id"])
    op.create_table("unload_events",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("event_id", sa.String(80), nullable=False, unique=True),
        sa.Column("compartment_id", sa.Integer, sa.ForeignKey("compartments.id"), nullable=False),
        sa.Column("tank_id", sa.Integer, sa.ForeignKey("receiving_tanks.id"), nullable=False),
        sa.Column("source_node_id", sa.Integer, sa.ForeignKey("material_nodes.id"), nullable=True),
        sa.Column("destination_node_id", sa.Integer, sa.ForeignKey("material_nodes.id"), nullable=True),
        sa.Column("amount", sa.Numeric(14, 3), nullable=False),
        sa.Column("observed_seal_code", sa.String(80), nullable=False),
        sa.Column("seal_intact", sa.Boolean, nullable=False),
        sa.Column("temperature_c", sa.Numeric(14, 3), nullable=True),
        sa.Column("weight_kg", sa.Numeric(14, 3), nullable=True),
        sa.Column("status", sa.String(32), nullable=False, server_default="accepted"),
        sa.Column("payload_hash", sa.String(64), nullable=False),
        sa.Column("recorded_by", sa.Integer, sa.ForeignKey("users.id"), nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    )
    op.create_table("topup_events",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("event_id", sa.String(80), nullable=False, unique=True),
        sa.Column("compartment_id", sa.Integer, sa.ForeignKey("compartments.id"), nullable=False),
        sa.Column("pasture_batch_id", sa.Integer, sa.ForeignKey("pasture_batches.id"), nullable=False),
        sa.Column("from_node_id", sa.Integer, sa.ForeignKey("material_nodes.id"), nullable=True),
        sa.Column("to_node_id", sa.Integer, sa.ForeignKey("material_nodes.id"), nullable=True),
        sa.Column("amount", sa.Numeric(14, 3), nullable=False),
        sa.Column("temperature_c", sa.Numeric(14, 3), nullable=True),
        sa.Column("status", sa.String(32), nullable=False, server_default="accepted"),
        sa.Column("payload_hash", sa.String(64), nullable=False),
        sa.Column("recorded_by", sa.Integer, sa.ForeignKey("users.id"), nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    )
    op.create_table("reconciliation_issues",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("issue_key", sa.String(120), nullable=False, unique=True),
        sa.Column("issue_type", sa.String(64), nullable=False),
        sa.Column("event_id", sa.String(80), nullable=True),
        sa.Column("status", sa.String(32), nullable=False, server_default="open"),
        sa.Column("details", JSONB, nullable=False, server_default="{}"),
        sa.Column("resolution", sa.Text, nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("resolved_by", sa.Integer, sa.ForeignKey("users.id"), nullable=True),
    )
    op.create_table("device_readings",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("reading_id", sa.String(80), nullable=False, unique=True),
        sa.Column("device_id", sa.String(80), nullable=False),
        sa.Column("metric", sa.String(32), nullable=False),
        sa.Column("value", sa.Numeric(14, 3), nullable=False),
        sa.Column("unit", sa.String(16), nullable=False),
        sa.Column("observed_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("replay_cursor", sa.Integer, nullable=True),
        sa.Column("received_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.CheckConstraint("metric in ('temperature','weight')", name="ck_device_metric"),
    )
    op.create_index("ix_device_readings_device", "device_readings", ["device_id"])
    op.create_table("samples",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("sample_code", sa.String(80), nullable=False, unique=True),
        sa.Column("compartment_id", sa.Integer, sa.ForeignKey("compartments.id"), nullable=False),
        sa.Column("node_id", sa.Integer, sa.ForeignKey("material_nodes.id"), nullable=False),
        sa.Column("status", sa.String(32), nullable=False, server_default="collected"),
        sa.Column("collected_by", sa.Integer, sa.ForeignKey("users.id"), nullable=False),
        sa.Column("collected_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("handover_by", sa.Integer, sa.ForeignKey("users.id"), nullable=True),
        sa.Column("handover_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("lab_received_by", sa.Integer, sa.ForeignKey("users.id"), nullable=True),
        sa.Column("lab_received_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("current_result_id", sa.Integer, nullable=True),
    )
    op.create_table("sample_results",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("sample_id", sa.Integer, sa.ForeignKey("samples.id"), nullable=False),
        sa.Column("revision", sa.Integer, nullable=False, server_default="1"),
        sa.Column("outcome", sa.String(32), nullable=False),
        sa.Column("analyst_id", sa.Integer, sa.ForeignKey("users.id"), nullable=False),
        sa.Column("notes", sa.Text, nullable=True),
        sa.Column("corrected", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("resulted_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.UniqueConstraint("sample_id", "revision", name="uq_sample_result_revision"),
        sa.CheckConstraint("outcome in ('pass','fail','conditional')", name="ck_sample_outcome"),
    )
    op.create_foreign_key("fk_samples_current_result", "samples", "sample_results",
                          ["current_result_id"], ["id"])
    op.create_table("receiving_decisions",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("arrival_id", sa.Integer, sa.ForeignKey("arrivals.id"), nullable=False, unique=True),
        sa.Column("conclusion", sa.String(32), nullable=False),
        sa.Column("notes", sa.Text, nullable=True),
        sa.Column("confirmed_by", sa.Integer, sa.ForeignKey("users.id"), nullable=False),
        sa.Column("confirmed_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.CheckConstraint("conclusion in ('accepted','conditional','rejected')", name="ck_decision_conclusion"),
    )
    op.create_table("release_requests",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("request_no", sa.String(80), nullable=False, unique=True),
        sa.Column("tank_id", sa.Integer, sa.ForeignKey("receiving_tanks.id"), nullable=False),
        sa.Column("node_id", sa.Integer, sa.ForeignKey("material_nodes.id"), nullable=False),
        sa.Column("status", sa.String(32), nullable=False, server_default="pending"),
        sa.Column("lineage_version", sa.String(64), nullable=False),
        sa.Column("reason", sa.Text, nullable=True),
        sa.Column("requested_by", sa.Integer, sa.ForeignKey("users.id"), nullable=False),
        sa.Column("decided_by", sa.Integer, sa.ForeignKey("users.id"), nullable=True),
        sa.Column("decision_notes", sa.Text, nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("decided_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("status in ('pending','approved','rejected','invalidated')", name="ck_release_status"),
    )


def downgrade() -> None:
    for table in [
        "release_requests", "receiving_decisions", "sample_results", "samples",
        "device_readings", "reconciliation_issues", "topup_events", "unload_events",
        "material_flows", "seals", "arrival_items", "arrivals",
        "compartments", "receiving_tanks", "material_nodes", "vehicles",
        "pasture_batches", "farms", "users",
    ]:
        op.drop_table(table)
