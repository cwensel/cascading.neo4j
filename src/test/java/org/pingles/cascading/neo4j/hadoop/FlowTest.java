package org.pingles.cascading.neo4j.hadoop;

import cascading.flow.Flow;
import cascading.operation.Identity;
import cascading.pipe.Each;
import cascading.pipe.Pipe;
import cascading.tap.Tap;
import cascading.test.HadoopPlatform;
import cascading.tuple.Fields;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.kernel.GraphDatabaseAPI;
import org.neo4j.rest.graphdb.RestGraphDatabase;
import org.neo4j.server.WrappingNeoServerBootstrapper;
import org.neo4j.server.configuration.Configurator;
import org.neo4j.server.configuration.ServerConfigurator;
import org.pingles.cascading.neo4j.IndexSpec;
import java.io.File;
import java.io.IOException;
import java.util.List;
import static junit.framework.Assert.*;
import static org.pingles.cascading.neo4j.local.Neo4jTestCase.toList;

@RunWith(JUnit4.class)
public class FlowTest {
    public static final String NEO4J_DB_DIR = "target/neo4jdb";
    public static final String REST_CONNECTION_STRING = "http://localhost:7575/db/data";
    private WrappingNeoServerBootstrapper server;
    private HadoopPlatform hadoopPlatform;
    private GraphDatabaseService graphDatabaseService;

    @Before
    public void beforeEach() throws IOException {
        hadoopPlatform = new HadoopPlatform();

        FileUtils.deleteDirectory(new File(NEO4J_DB_DIR));
        GraphDatabaseAPI graphdb = (GraphDatabaseAPI) new GraphDatabaseFactory()
                .newEmbeddedDatabaseBuilder(NEO4J_DB_DIR)
                .newGraphDatabase();
        ServerConfigurator config = new ServerConfigurator( graphdb );
        config.configuration().setProperty(Configurator.WEBSERVER_PORT_PROPERTY_KEY, 7575);
        server = new WrappingNeoServerBootstrapper(graphdb, config);
        server.start();
    }

    @After
    public void afterEach() throws IOException {
        neoService().index().forNodes("users").delete();    // CAUTION hard coded
        neoService().index().forNodes("nations").delete();

        server.stop();
        FileUtils.deleteDirectory(new File(NEO4J_DB_DIR));
    }

    @Test
    public void shouldStoreNodes() {
        Fields sourceFields = new Fields("name");

        flowNodes(sourceFields, "src/test/resources/names.csv", sourceFields);

        assertEquals(2 + 1, toList(neoService().getAllNodes()).size());
    }

    @Test
    public void shouldStoreNodeWithMultipleProperties() {
        Fields sourceFields = new Fields("name", "nationality", "relationshipLabel");
        String filename = "src/test/resources/names_and_nationality.csv";

        flowNodes(sourceFields, filename, sourceFields);

        Node node = neoService().getNodeById(1);
        assertEquals(1, node.getId());
        assertEquals("pingles", node.getProperty("name"));
        assertEquals("british", node.getProperty("nationality"));
    }

    @Test
    public void shouldStoreNodeWithIndexes() {
        Fields sourceFields = new Fields("name", "nationality", "relationshipLabel");
        String filename = "src/test/resources/names_and_nationality.csv";
        IndexSpec indexSpec = new IndexSpec("users", new Fields("name", "nationality"));

        flowNodes(sourceFields, sourceFields, filename, indexSpec);

        List<Node> nodes = toList(neoService().index().forNodes("users").get("nationality", "british"));

        assertEquals(2, nodes.size());
        assertEquals("pingles", nodes.get(0).getProperty("name"));
        assertEquals("angrymike", nodes.get(1).getProperty("name"));
    }

    @Test
    public void shouldCreateRelationBetweenNodes() {
        Fields nameField = new Fields("name");
        Fields relFields = new Fields("name", "nationality", "relationship");

        IndexSpec usersIndex = new IndexSpec("users", nameField);
        IndexSpec nationsIndex = new IndexSpec("nations", nameField);

        flowNodes(relFields, nameField, "src/test/resources/names_and_nationality.csv", usersIndex);
        flowNodes(nameField, nameField, "src/test/resources/nationalities.csv", nationsIndex);

        flowRelations(relFields, "src/test/resources/names_and_nationality.csv", usersIndex, nationsIndex);

        Node pingles = neoService().index().forNodes("users").get("name", "pingles").getSingle();
        List<Relationship> relationships = toList(pingles.getRelationships());
        assertEquals(1, relationships.size());
        assertEquals("british", relationships.get(0).getEndNode().getProperty("name"));
        assertEquals("NATIONALITY", relationships.get(0).getType().name());
    }

    @Test
    public void shouldCreateRelationshipsWithAdditionalProperties() {
        Fields nameField = new Fields("name");
        Fields relFields = new Fields("name", "nationality", "relationship", "yearsofcitizenship", "passportexpiring");

        IndexSpec usersIndex = new IndexSpec("users", nameField);
        IndexSpec nationsIndex = new IndexSpec("nations", nameField);

        flowNodes(relFields, nameField, "src/test/resources/names_nations_and_more.csv", usersIndex);
        flowNodes(nameField, nameField, "src/test/resources/nationalities.csv", nationsIndex);

        flowRelations(relFields, "src/test/resources/names_nations_and_more.csv", usersIndex, nationsIndex);

        Node pingles = neoService().index().forNodes("users").get("name", "pingles").getSingle();
        List<Relationship> relationships = toList(pingles.getRelationships());
        assertEquals(1, relationships.size());
        assertEquals("british", relationships.get(0).getEndNode().getProperty("name"));
        assertEquals("NATIONALITY", relationships.get(0).getType().name());
        assertEquals("31", relationships.get(0).getProperty("yearsofcitizenship"));
        assertEquals("3", relationships.get(0).getProperty("passportexpiring"));
    }

    private void flowRelations(Fields relationshipFields, String filename, IndexSpec fromIndex, IndexSpec toIndex) {
        Tap relationsTap = hadoopPlatform.getDelimitedFile(relationshipFields, ",", filename);
        Tap sinkTap = new Neo4jTap(REST_CONNECTION_STRING, new Neo4jRelationshipScheme(relationshipFields, fromIndex, toIndex));
        Pipe nodePipe = new Each("relations", relationshipFields, new Identity());
        Flow nodeFlow = hadoopPlatform.getFlowConnector().connect(relationsTap, sinkTap, nodePipe);
        nodeFlow.complete();
    }

    private void flowNodes(Fields sourceFields, String filename, Fields outgoingFields) {
        flowNodes(sourceFields, outgoingFields, filename, null);
    }

    private void flowNodes(Fields sourceFields, Fields outgoingFields, String filename, IndexSpec indexSpec) {
        Neo4jNodeScheme scheme;
        if (indexSpec != null) {
            scheme = new Neo4jNodeScheme(indexSpec);
        } else {
            scheme = new Neo4jNodeScheme();
        }

        Tap nodeSourceTap = hadoopPlatform.getDelimitedFile(sourceFields, ",", filename);
        Tap nodeSinkTap = new Neo4jTap(REST_CONNECTION_STRING, scheme);
        Pipe nodePipe = new Each("Nodes", outgoingFields, new Identity());
        Flow nodeFlow = hadoopPlatform.getFlowConnector().connect(nodeSourceTap, nodeSinkTap, nodePipe);
        nodeFlow.complete();
    }

    protected GraphDatabaseService neoService() {
        if (graphDatabaseService != null) {
            return graphDatabaseService;
        }
        graphDatabaseService = new RestGraphDatabase(REST_CONNECTION_STRING);
        return graphDatabaseService;
    }
}