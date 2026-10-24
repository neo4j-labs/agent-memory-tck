# Keep entity types open

The Java client represents an entity's type as a plain `String` and does not restrict search filters or returned entities to a client-defined enum. Entity types originate in knowledge-graph discovery and may expand independently of a client release, so a closed Java vocabulary would turn additive hosted knowledge into request or decoding failures.
