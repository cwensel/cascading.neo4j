package org.pingles.cascading.neo4j;

import cascading.tuple.Fields;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import static junit.framework.Assert.*;

@RunWith(JUnit4.class)
public class Neo4jRelationshipSchemeTest {
    @Test
    public void shouldThrowExceptionWhenNotEnoughFieldsProvidedForRelationship() {
        // to draw a relationship we need:
        // from, to, relationship name
        // any less and we can't draw the relation
        try {
            new Neo4jRelationshipScheme(null, new Fields("blah"), new IndexSpec("users", new Fields()));
        } catch (IllegalArgumentException e) {
            return;
        }
        fail("Didn't throw IllegalArgumentException");
    }

    @Test
    public void shouldNotThrowExceptionWhenFieldsSpecifiesEnough() {
        try {
            new Neo4jRelationshipScheme(null, new Fields("fromName", "toName", "relationshipLabel"), new IndexSpec("users", new Fields()));
        } catch (IllegalArgumentException e) {
            fail(e.getMessage());
        }
    }

    @Test
    public void shouldAllowMoreThan3Fields() {
        // if we pass more than 3 fields, anything to the right will be added
        // as a property of the relationship.
        try {
            new Neo4jRelationshipScheme(null, new Fields("fromName", "toName", "relationshipLabel", "property1", "property2"), new IndexSpec("users", new Fields()));
        } catch (IllegalArgumentException e) {
            fail(e.getMessage());
        }
    }
}
