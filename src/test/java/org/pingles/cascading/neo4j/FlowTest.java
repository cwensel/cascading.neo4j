package org.pingles.cascading.neo4j;

import cascading.flow.Flow;
import cascading.operation.Identity;
import cascading.pipe.Each;
import cascading.pipe.Pipe;
import cascading.tap.Tap;
import cascading.test.LocalPlatform;
import cascading.tuple.Fields;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.index.IndexHits;

import java.util.ArrayList;
import java.util.List;

import static junit.framework.Assert.*;

@RunWith(JUnit4.class)
public class FlowTest extends Neo4jTest {

    private LocalPlatform localPlatform;

    @Before
    public void initializePlatform() {
        localPlatform = new LocalPlatform();
    }

    @Test
    public void shouldSinkContentsToNeo() {
        Fields userFields = new Fields("name");
        flowNodes("Users", "src/test/resources/names.csv", userFields, userFields);

        assertEquals("pingles", neo4j.getNode(1).getProperty("name"));
        assertEquals("plam", neo4j.getNode(2).getProperty("name"));
    }

    @Test
    public void shouldCreateNodesAndIndexOnSpecifiedField() {
        Fields userFields = new Fields("name");
        flowNodes("Users", "src/test/resources/names.csv", userFields, userFields, new IndexSpec("users", userFields));

        IndexHits<Node> nodes = neo4j.indexForNodes("users").get("name", "pingles");
        assertEquals(1, nodes.size());

        Node pinglesNode = nodes.getSingle();
        assertEquals("pingles", pinglesNode.getProperty("name"));
    }

    @Test
    public void shouldCreateRelationshipsBetweenNodes() {
        Fields userFields = new Fields("name");
        flowNodes("Users", "src/test/resources/names.csv", userFields, userFields, new IndexSpec("users", userFields));
        flowRelations("Relations", "src/test/resources/relations.csv", new Fields("from", "to", "relationshipType"), new IndexSpec("users", userFields));

        // lookup one of the nodes and check the association
        IndexHits<Node> nodes = neo4j.indexForNodes("users").get("name", "pingles");
        Node pinglesNode = nodes.getSingle();

        List<Relationship> relationships = toList(pinglesNode.getRelationships());
        assertEquals(1, relationships.size());
        assertEquals("plam", relationships.get(0).getEndNode().getProperty("name"));
    }
    @Test
    public void shouldCreateMultipleIndexes() {
        Fields sourceFields = new Fields("name", "nationality", "relationship");
        flowNodes("Users", "src/test/resources/names_and_nationality.csv", sourceFields, sourceFields, new IndexSpec("users", sourceFields));

        IndexHits<Node> nodes = neo4j.indexForNodes("users").get("nationality", "british");
        assertEquals(2, nodes.size());

        List<Node> nodeList = toList(nodes);
        assertEquals("pingles", nodeList.get(0).getProperty("name"));
        assertEquals("british", nodeList.get(0).getProperty("nationality"));
        assertEquals("angrymike", nodeList.get(1).getProperty("name"));
        assertEquals("british", nodeList.get(1).getProperty("nationality"));
    }

    @Test
    public void shouldCreateRelationshipsBetweenDifferentNodeTypes() {
        Fields nameFields = new Fields("name");
        flowNodes("Users", "src/test/resources/names_and_nationality.csv", new Fields("name", "nationality"), nameFields, new IndexSpec("users", nameFields));
        flowNodes("Nationalities", "src/test/resources/nationalities.csv", nameFields, nameFields, new IndexSpec("nationalities", nameFields));
        IndexSpec fromIndexSpec = new IndexSpec("users", nameFields);
        IndexSpec toIndexSpec = new IndexSpec("nationalities", nameFields);
        flowRelations("Relations", "src/test/resources/names_and_nationality.csv", new Fields("person", "country", "relationship"), fromIndexSpec, toIndexSpec);

        IndexHits<Node> britishNodes = neo4j.indexForNodes("nationalities").get("name", "british");
        Node britishNode = britishNodes.getSingle();
        assertEquals(2, toList(britishNode.getRelationships()).size());

        Node plamNode = neo4j.indexForNodes("users").get("name", "plam").getSingle();
        assertNotNull(plamNode);
        assertEquals("plam", plamNode.getProperty("name"));
        List<Relationship> relationships = toList(plamNode.getRelationships());
        assertEquals(1, relationships.size());
        assertEquals("NATIONALITY", relationships.get(0).getType().name());
    }

    @Test
    public void shouldCreateDirectionalRelationships() {
        Fields nameFields = new Fields("name");
        flowNodes("Users", "src/test/resources/names_and_nationality.csv", new Fields("name", "nationality"), nameFields, new IndexSpec("users", nameFields));
        flowNodes("Nationalities", "src/test/resources/nationalities.csv", nameFields, nameFields, new IndexSpec("nationalities", nameFields));
        IndexSpec fromIndexSpec = new IndexSpec("users", nameFields);
        IndexSpec toIndexSpec = new IndexSpec("nationalities", nameFields);
        flowRelations("Relations", "src/test/resources/names_and_nationality.csv", new Fields("person", "country", "relationship"), fromIndexSpec, toIndexSpec);

        Node plamNode = neo4j.indexForNodes("users").get("name", "plam").getSingle();

        assertEquals(0, toList(plamNode.getRelationships(Direction.INCOMING)).size());
        assertEquals(1, toList(plamNode.getRelationships(Direction.OUTGOING)).size());
    }

    @Test
    public void shouldCreateRelationshipsWithProperties() {
        Fields nameFields = new Fields("name");
        IndexSpec userIndex = new IndexSpec("users", nameFields);
        IndexSpec nationIndex = new IndexSpec("nationalities", nameFields);

        flowNodes("Users", "src/test/resources/names_and_nationality.csv", new Fields("name", "nationality"), nameFields, userIndex);
        flowNodes("Nationalities", "src/test/resources/nationalities.csv", nameFields, nameFields, nationIndex);

        Fields relationshipFields = new Fields("person", "country", "relationship", "yearsofcitizenship", "passportexpiring");
        flowRelations("Relations", "src/test/resources/names_nations_and_more.csv", relationshipFields, userIndex, nationIndex);

        Node plam = neo4j.indexForNodes("users").get("name", "plam").getSingle();
        Relationship citizenship = toList(plam.getRelationships()).get(0);

        assertEquals("31", citizenship.getProperty("yearsofcitizenship"));
        assertEquals("1", citizenship.getProperty("passportexpiring"));
    }

    private void flowRelations(String name, String filename, Fields sourceFields, IndexSpec indexspec) {
        flowRelations(name, filename, sourceFields, indexspec, indexspec);
    }

    private void flowRelations(String name, String filename, Fields sourceFields, IndexSpec fromIndexSpec, IndexSpec toIndexSpec) {
        Tap relationshipSourceTap = localPlatform.getDelimitedFile(sourceFields, ",", filename);
        Tap relationshipSinkTap = new Neo4jTap(new Neo4jRelationshipScheme(this.neo4j.getService(), sourceFields, fromIndexSpec, toIndexSpec));
        flowThroughPipe(name, sourceFields, relationshipSourceTap, relationshipSinkTap);
    }

    private void flowNodes(String name, String filename, Fields sourceFields, Fields outFields) {
        Tap nodeSourceTap = localPlatform.getDelimitedFile(sourceFields, ",", filename);
        Tap nodeSinkTap = new Neo4jTap(new Neo4jNodeScheme(neo4j.getService()));
        flowThroughPipe(name, outFields, nodeSourceTap, nodeSinkTap);
    }

    private void flowNodes(String name, String filename, Fields sourceFields, Fields outFields, IndexSpec indexSpec) {
        Tap nodeSourceTap = localPlatform.getDelimitedFile(sourceFields, ",", filename);
        Tap nodeSinkTap = new Neo4jTap(new Neo4jNodeScheme(neo4j.getService(), indexSpec));
        flowThroughPipe(name, outFields, nodeSourceTap, nodeSinkTap);
    }

    private void flowThroughPipe(String name, Fields outFields, Tap nodeSourceTap, Tap nodeSinkTap) {
        Pipe nodePipe = new Each(name, outFields, new Identity());
        Flow nodeFlow = localPlatform.getFlowConnector().connect(nodeSourceTap, nodeSinkTap, nodePipe);
        nodeFlow.complete();
    }

    static <T> List<T> toList(Iterable<T> c) {
        List<T> list = new ArrayList<T>();
        for (T o : c) {
            list.add(o);
        }
        return list;
    }
}
