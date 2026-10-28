package org.neo4j.agentmemory.entity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EntitySearchTest {
    private static final UUID ID =
            UUID.fromString("2bdc3216-d03b-44ca-a335-6dfad86390d8");

    @Test
    void offersTypedAndUntypedSearchConstructors() {
        assertThat(new EntitySearch("alice"))
                .extracting(EntitySearch::query, EntitySearch::type, EntitySearch::limit)
                .containsExactly("alice", null, 10);
        assertThat(new EntitySearch("alice", 3).limit()).isEqualTo(3);
        assertThat(new EntitySearch("alice", "person").type()).isEqualTo("person");
        assertThat(new EntitySearch("alice", "custom-type", 4).limit()).isEqualTo(4);
    }

    @Test
    void keepsEntityTypesOpenAndDescriptionsOptional() {
        var entity = new Entity(ID, "Apollo", "future-type", (String) null);

        assertThat(Entity.class.isRecord()).isTrue();
        assertThat(entity.type()).isEqualTo("future-type");
        assertThat(entity.description()).isNull();
        assertThat(entity.getDescription()).isEmpty();
    }

    @Test
    void entitiesCompareByUuid() {
        var first = new Entity(ID, "Apollo", "project", "first");
        var second = new Entity(ID, "Renamed", "custom", (String) null);

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }
}
