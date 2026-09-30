from __future__ import annotations

import uuid
from decimal import Decimal

from sqlalchemy import text
from sqlalchemy.orm import Session


def downstream_tank_ids(db: Session, source_node_id: uuid.UUID, farm_batch_id: uuid.UUID | None = None) -> list[uuid.UUID]:
    """Physical tanks whose current node is downstream of a sampled source."""
    params = {"source": str(source_node_id)}
    batch_join = ""
    if farm_batch_id:
        params["batch"] = str(farm_batch_id)
        batch_join = """
          JOIN batch_flow bf ON bf.node_id = d.node_id
        """
    batch_cte = ""
    if farm_batch_id:
        batch_cte = """, batch_flow AS (
          WITH RECURSIVE bf AS (
            SELECT n.id AS node_id, n.quantity::numeric AS amount
            FROM material_nodes n
            WHERE n.farm_batch_id=CAST(:batch AS uuid) AND n.type='FARM_BATCH'
            UNION ALL
            SELECT e.destination_node_id, b.amount * e.quantity / src.quantity
            FROM bf b
            JOIN material_edges e ON e.source_node_id=b.node_id
            JOIN material_nodes src ON src.id=b.node_id
            WHERE src.quantity > 0
          )
          SELECT node_id, SUM(amount) AS amount FROM bf GROUP BY node_id
          HAVING SUM(amount) > 0.000001
        )
        """
    sql = text(f"""
    WITH RECURSIVE downstream AS (
      SELECT CAST(:source AS uuid) AS node_id
      UNION
      SELECT e.destination_node_id
      FROM material_edges e JOIN downstream d ON e.source_node_id = d.node_id
      WHERE e.edge_kind = 'TRANSFER'
    ){batch_cte}
    SELECT DISTINCT t.id
    FROM receiving_tanks t
    JOIN downstream d ON d.node_id = t.current_node_id
    JOIN material_nodes n ON n.id = d.node_id AND n.type='TANK'
    {batch_join}
    """)
    return list(db.execute(sql, params).scalars())


def has_rejected_ancestor_sample(db: Session, node_id: uuid.UUID) -> bool:
    sql = text("""
    WITH RECURSIVE ancestors AS (
      SELECT CAST(:node AS uuid) AS node_id
      UNION
      SELECT e.source_node_id
      FROM material_edges e JOIN ancestors a ON e.destination_node_id = a.node_id
      WHERE e.source_node_id IS NOT NULL
    )
    SELECT 1
    FROM samples s
    JOIN lab_results lr ON lr.sample_id=s.id AND lr.version=s.current_version
    WHERE lr.result='REJECT'
      AND (s.source_node_id IN (SELECT node_id FROM ancestors)
           OR s.sample_node_id IN (SELECT node_id FROM ancestors))
    LIMIT 1
    """)
    return db.execute(sql, {"node": str(node_id)}).first() is not None


def abnormal_impact(db: Session, sample_id: uuid.UUID) -> dict:
    sample = db.execute(text("""
        SELECT id, source_node_id, farm_batch_id FROM samples WHERE id=:id
    """), {"id": str(sample_id)}).mappings().one()
    source_id = sample.source_node_id
    batch_id = sample.farm_batch_id

    impact_sql = text("""
    WITH RECURSIVE
    ancestors AS (
      SELECT CAST(:source AS uuid) AS node_id
      UNION
      SELECT e.source_node_id
      FROM material_edges e JOIN ancestors a ON e.destination_node_id = a.node_id
      WHERE e.source_node_id IS NOT NULL
    ),
    downstream AS (
      SELECT CAST(:source AS uuid) AS node_id
      UNION
      SELECT e.destination_node_id
      FROM material_edges e JOIN downstream d ON e.source_node_id = d.node_id
      WHERE e.edge_kind = 'TRANSFER'
    ),
    impacted AS (
      SELECT node_id FROM ancestors
      UNION SELECT node_id FROM downstream
    )
    SELECT n.id, n.type, n.quantity, n.farm_batch_id,
           COALESCE((SELECT sum(quantity) FROM material_edges
                     WHERE source_node_id=n.id AND edge_kind='TRANSFER'),0) AS outgoing
    FROM impacted i JOIN material_nodes n ON n.id=i.node_id
    """)
    rows = [dict(r) for r in db.execute(impact_sql, {"source": str(source_id)}).mappings()]

    batch_amounts: dict[uuid.UUID, Decimal] = {}
    if batch_id:
        # Contribution is recomputed from the farm source, including
        # continuation edges, so partial unload and mixing allocate proportionally.
        flow_sql = text("""
        WITH RECURSIVE flow AS (
          SELECT n.id AS node_id, n.quantity::numeric AS amount
          FROM material_nodes n
          WHERE n.farm_batch_id=CAST(:batch AS uuid) AND n.type='FARM_BATCH'
          UNION ALL
          SELECT e.destination_node_id,
                 f.amount * e.quantity / src.quantity
          FROM flow f
          JOIN material_edges e ON e.source_node_id=f.node_id
          JOIN material_nodes src ON src.id=f.node_id
          WHERE src.quantity > 0
        )
        SELECT node_id, SUM(amount) AS amount FROM flow GROUP BY node_id
        HAVING SUM(amount) > 0.000001
        """)
        batch_amounts = {r["node_id"]: Decimal(r["amount"]) for r in db.execute(
            flow_sql, {"batch": str(batch_id)}).mappings()}
        rows = [r for r in rows if r["id"] in batch_amounts]

    tank_node_ids: set[uuid.UUID] = set()
    remaining_compartments = []
    for row in rows:
        qty = Decimal(row["quantity"])
        outgoing = Decimal(row["outgoing"])
        remaining = qty - outgoing
        if str(row["type"]) == "TANK" and outgoing == 0 and _is_current_tank(db, row["id"]):
            tank_node_ids.add(row["id"])
        elif str(row["type"]) == "COMPARTMENT" and remaining > 0:
            affected = batch_amounts.get(row["id"], remaining)
            remaining_compartments.append({
                "node_id": str(row["id"]),
                "remaining_quantity": _money(remaining),
                "affected_quantity": _money(min(affected, remaining)),
            })

    tanks_out = []
    if tank_node_ids:
        tank_rows = db.execute(text("""
            SELECT id, tank_code, name, lineage_version, current_node_id
            FROM receiving_tanks WHERE current_node_id = ANY(:ids)
            ORDER BY tank_code
        """), {"ids": list(tank_node_ids)}).mappings().all()
        for tank in tank_rows:
            node = next(n for n in rows if n["id"] == tank["current_node_id"])
            qty = Decimal(node["quantity"])
            affected = batch_amounts.get(tank["current_node_id"], qty)
            tanks_out.append({
                "tank_id": str(tank["id"]),
                "tank_code": tank["tank_code"],
                "name": tank["name"],
                "lineage_version": tank["lineage_version"],
                "current_node_id": str(tank["current_node_id"]),
                "quantity": _money(qty),
                "affected_quantity": _money(min(affected, qty)),
            })

    return {
        "sample_id": str(sample_id),
        "source_node_id": str(source_id),
        "farm_batch_id": str(batch_id) if batch_id else None,
        "receiving_tanks": tanks_out,
        "remaining_compartments": remaining_compartments,
    }


def _is_current_tank(db: Session, node_id: uuid.UUID) -> bool:
    return db.execute(text("SELECT 1 FROM receiving_tanks WHERE current_node_id=:id"),
                      {"id": str(node_id)}).first() is not None


def _money(value: Decimal) -> float:
    return float(value.quantize(Decimal("0.001")))
