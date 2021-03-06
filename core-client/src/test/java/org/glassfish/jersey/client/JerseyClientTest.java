/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011-2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.jersey.client;

import java.io.IOException;
import java.util.concurrent.Future;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientException;
import javax.ws.rs.client.ClientFactory;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Link;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import javax.inject.Inject;

import org.glassfish.jersey.client.spi.AsyncConnectorCallback;
import org.glassfish.jersey.client.spi.Connector;
import org.glassfish.jersey.internal.Version;
import org.glassfish.jersey.internal.inject.AbstractBinder;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.net.HttpHeaders;

/**
 * @author Marek Potociar (marek.potociar at oracle.com)
 */
public class JerseyClientTest {

    private JerseyClient client;

    public JerseyClientTest() {
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
    }

    @Before
    public void setUp() {
        this.client = (JerseyClient) ClientFactory.newClient();
    }

    @After
    public void tearDown() {
    }

    @Test
    public void testCreateClient() {
        assertNotNull(client);
    }

    @Test
    public void testClose() {
        client.close();
        assertTrue(client.isClosed());
        client.close(); // closing multiple times is ok

        try {
            client.getConfiguration();
            fail("IllegalStateException expected if a method is called on a closed client instance.");
        } catch (IllegalStateException ex) {
            // ignored
        }
        try {
            client.target("http://jersey.java.net/examples");
            fail("IllegalStateException expected if a method is called on a closed client instance.");
        } catch (IllegalStateException ex) {
            // ignored
        }
    }

    @Test
    public void testConfiguration() {
        final ClientConfig configuration = client.getConfiguration();
        assertNotNull(configuration);

        configuration.setProperty("hello", "world");

        assertEquals("world", client.getConfiguration().getProperty("hello"));
    }

    @Test
    public void testTarget() {
        final JerseyWebTarget target = client.target("http://jersey.java.net/examples");
        assertNotNull(target);
        assertEquals(client.getConfiguration(), target.getConfiguration());
    }

    @Test
    public void testTargetConfigUpdate() {
        final JerseyWebTarget target = client.target("http://jersey.java.net/examples");

        target.getConfiguration().register(new ClientRequestFilter() {
            @Override
            public void filter(ClientRequestContext clientRequestContext) throws IOException {
                throw new UnsupportedOperationException("Not supported yet");
            }
        });

        assertEquals(1, target.getConfiguration().getInstances().size());
    }

    /**
     * Regression test for JERSEY-1192.
     */
    @Test
    public void testCreateLinkBasedInvocation() {
        JerseyClient client = new JerseyClient();

        try {
            client.invocation(null);
            fail("NullPointerException expected.");
        } catch (NullPointerException ex) {
            // success.
        }

        try {
            client.invocation(null);
            fail("NullPointerException expected.");
        } catch (NullPointerException ex) {
            // success.
        }

        Link link1 =
                Link.fromUri(UriBuilder.fromPath("http://localhost:8080/").build())
                        .build();
        Link link2 =
                Link.fromUri(UriBuilder.fromPath("http://localhost:8080/").build())
                        .type("text/plain")
                        .build();


        assertNotNull(client.invocation(link1).buildPost(null));
        assertNotNull(client.invocation(link2).buildPost(null));

        assertNotNull(client.invocation(link1).buildPost(Entity.text("Test.")));
        assertNotNull(client.invocation(link2).buildPost(Entity.text("Test.")));

        assertNotNull(client.invocation(link1).buildPost(Entity.xml("Test.")));
        assertNotNull(client.invocation(link2).buildPost(Entity.xml("Test.")));
    }

    @Test
    public void userAgentTest() {
        Client client = ClientFactory.newClient(new ClientConfig().connector(new Connector() {
            @Override
            public ClientResponse apply(ClientRequest request) throws ClientException {
                throw new ClientException(request.getHeaders().getFirst(HttpHeaders.USER_AGENT).toString(), null);
            }

            @Override
            public Future<?> apply(ClientRequest request, AsyncConnectorCallback callback) {
                callback.failure(new ClientException(request.getHeaders().getFirst(HttpHeaders.USER_AGENT).toString(), null));
                return null;
            }

            @Override
            public void close() {
                // nothing
            }

            @Override
            public String getName() {
                return null;
            }
        }));

        try {
            client.target("test").request().get();
        } catch (ClientException e) {
            assertEquals("Jersey/" + Version.getVersion(), e.getMessage());
        }

        try {
            client.target("test").request().async().get().get();
        } catch (Exception e) {
            assertEquals("Jersey/" + Version.getVersion(), e.getCause().getMessage());
        }
    }

    public static interface CustomContract {
        public String getFoo();
    }

    public static class CustomService implements CustomContract {

        @Override
        public String getFoo() {
            return "Foo";
        }
    }

    public static class CustomBinder extends AbstractBinder {

        @Override
        protected void configure() {
            bind(CustomService.class).to(CustomContract.class);
        }
    }

    public static class CustomProvider implements ClientRequestFilter {
        @Inject
        private CustomContract customContract;

        @Override
        public void filter(ClientRequestContext requestContext) throws IOException {
            requestContext.abortWith(Response.ok(customContract.getFoo()).build());
        }
    }

    @Test
    public void testCustomBinders() {
        final CustomBinder binder = new CustomBinder();
        Client client = ClientFactory.newClient().register(binder).register(CustomProvider.class);

        Response resp = client.target("test").request().get();
        assertEquals("Foo", resp.readEntity(String.class));
    }
}
