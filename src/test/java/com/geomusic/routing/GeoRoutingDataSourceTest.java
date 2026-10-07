package com.geomusic.routing;

import com.geomusic.domain.Region;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.geomusic.routing.DbNode.master;
import static com.geomusic.routing.DbNode.replica;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeoRoutingDataSourceTest {

    private final Map<DbNode, DataSource> dataSources = new LinkedHashMap<>();
    private final Map<DbNode, Connection> connections = new LinkedHashMap<>();
    private final List<String> routed = new ArrayList<>();
    private NodeHealthRegistry health;
    private GeoRoutingDataSource routing;

    @BeforeEach
    void setUp() throws SQLException {
        for (Region region : Region.values()) {
            node(master(region));
            node(replica(region, 1));
            node(replica(region, 2));
        }
        GeoNodeRegistry nodes = new GeoNodeRegistry(dataSources);
        health = new NodeHealthRegistry(nodes.all());
        RoutingListener recorder = new RoutingListener() {
            @Override
            public void onConnectionRouted(RoutingContext.Route route, DbNode node, int failedAttempts) {
                routed.add(node.id() + (failedAttempts > 0 ? " (failover)" : ""));
            }
        };
        routing = new GeoRoutingDataSource(nodes, health, recorder, Region.EURO);
    }

    @AfterEach
    void tearDown() {
        RoutingContext.clear();
    }

    @Test
    void readsAreBalancedRoundRobinAcrossReplicasOfUserRegion() throws SQLException {
        RoutingContext.bindRequest(Region.ASIA, AccessMode.READ);

        List<Connection> served = List.of(
                routing.getConnection(), routing.getConnection(), routing.getConnection(), routing.getConnection());

        assertThat(served).containsExactly(
                connection(replica(Region.ASIA, 1)), connection(replica(Region.ASIA, 2)),
                connection(replica(Region.ASIA, 1)), connection(replica(Region.ASIA, 2)));
    }

    @Test
    void readFailsOverToSecondReplicaAndSkipsDeadReplicaAfterwards() throws SQLException {
        refuse(replica(Region.EURO, 1));
        RoutingContext.bindRequest(Region.EURO, AccessMode.READ);

        assertThat(routing.getConnection()).isSameAs(connection(replica(Region.EURO, 2)));
        assertThat(health.isAvailable(replica(Region.EURO, 1))).isFalse();
        assertThat(routing.getConnection()).isSameAs(connection(replica(Region.EURO, 2)));
        assertThat(routing.getConnection()).isSameAs(connection(replica(Region.EURO, 2)));
        assertThat(routed).containsExactly("euro-replica2 (failover)", "euro-replica2", "euro-replica2");
    }

    @Test
    void readFallsBackToLocalMasterWhenAllReplicasAreDown() throws SQLException {
        refuse(replica(Region.AMER, 1));
        refuse(replica(Region.AMER, 2));
        RoutingContext.bindRequest(Region.AMER, AccessMode.READ);

        assertThat(routing.getConnection()).isSameAs(connection(master(Region.AMER)));
    }

    @Test
    void readFailsWhenWholeRegionIsDown() throws SQLException {
        refuse(replica(Region.ASIA, 1));
        refuse(replica(Region.ASIA, 2));
        refuse(master(Region.ASIA));
        RoutingContext.bindRequest(Region.ASIA, AccessMode.READ);

        assertThatThrownBy(routing::getConnection)
                .isInstanceOfSatisfying(NodeUnavailableException.class, ex -> {
                    assertThat(ex.getRegion()).isEqualTo(Region.ASIA);
                    assertThat(ex.getMode()).isEqualTo(AccessMode.READ);
                    assertThat(ex.getAttemptedNodes())
                            .containsExactly("asia-replica1", "asia-replica2", "asia-master");
                });
    }

    @Test
    void writeGoesToMasterOfObjectRegionRegardlessOfUserRegion() throws SQLException {
        RoutingContext.bindRequest(Region.EURO, AccessMode.WRITE);

        try (RoutingContext.Scope ignored = RoutingContext.use(new RoutingContext.Route(Region.ASIA, AccessMode.WRITE))) {
            assertThat(routing.getConnection()).isSameAs(connection(master(Region.ASIA)));
        }
        assertThat(routing.getConnection()).isSameAs(connection(master(Region.EURO)));
    }

    @Test
    void writeFailsWithoutFailoverWhenMasterIsDown() throws SQLException {
        refuse(master(Region.AMER));

        try (RoutingContext.Scope ignored = RoutingContext.use(new RoutingContext.Route(Region.AMER, AccessMode.WRITE))) {
            assertThatThrownBy(routing::getConnection)
                    .isInstanceOfSatisfying(NodeUnavailableException.class, ex -> {
                        assertThat(ex.getMode()).isEqualTo(AccessMode.WRITE);
                        assertThat(ex.getAttemptedNodes()).containsExactly("amer-master");
                        assertThat(ex.getSQLState()).isEqualTo("08001");
                    });
        }
    }

    @Test
    void recoveredReplicaReturnsToRotationAfterSuccessfulProbe() throws SQLException {
        health.markDown(replica(Region.EURO, 1), new SQLException("down"));
        RoutingContext.bindRequest(Region.EURO, AccessMode.READ);
        assertThat(routing.getConnection()).isSameAs(connection(replica(Region.EURO, 2)));

        health.recordProbeSuccess(replica(Region.EURO, 1), java.time.Duration.ofMillis(3));

        assertThat(List.of(routing.getConnection(), routing.getConnection()))
                .containsExactlyInAnyOrder(connection(replica(Region.EURO, 1)), connection(replica(Region.EURO, 2)));
    }

    @Test
    void withoutContextUsesMasterOfDefaultRegion() throws SQLException {
        assertThat(routing.getConnection()).isSameAs(connection(master(Region.EURO)));
        assertThat(routing.determineCurrentLookupKey()).isEqualTo("euro-master");
    }

    private void node(DbNode node) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        dataSources.put(node, dataSource);
        connections.put(node, connection);
    }

    private void refuse(DbNode node) throws SQLException {
        when(dataSources.get(node).getConnection())
                .thenThrow(new SQLTransientConnectionException(node.id() + " - Connection is not available"));
    }

    private Connection connection(DbNode node) {
        return connections.get(node);
    }
}
