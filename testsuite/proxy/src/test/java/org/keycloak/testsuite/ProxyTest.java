/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.keycloak.testsuite;

import io.undertow.Undertow;
import io.undertow.io.IoCallback;
import io.undertow.security.api.SecurityContext;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.server.handlers.proxy.SimpleProxyClientProvider;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.apache.catalina.startup.Tomcat;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.keycloak.KeycloakSecurityContext;
import org.keycloak.OAuth2Constants;
import org.keycloak.adapters.KeycloakDeploymentBuilder;
import org.keycloak.adapters.undertow.AbstractUndertowRequestAuthenticator;
import org.keycloak.adapters.undertow.UndertowHttpFacade;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.protocol.oidc.OpenIDConnectService;
import org.keycloak.proxy.ProxyServerBuilder;
import org.keycloak.representations.adapters.config.AdapterConfig;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.testsuite.pages.LoginPage;
import org.keycloak.testsuite.rule.AbstractKeycloakRule;
import org.keycloak.testsuite.rule.WebResource;
import org.keycloak.testsuite.rule.WebRule;
import org.keycloak.testutils.KeycloakServer;
import org.openqa.selenium.WebDriver;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.UriBuilder;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.security.Principal;
import java.util.regex.Matcher;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class ProxyTest {
    static String logoutUri = OpenIDConnectService.logoutUrl(UriBuilder.fromUri("http://localhost:8081/auth"))
            .queryParam(OAuth2Constants.REDIRECT_URI, "http://localhost:8080/customer-portal").build("demo").toString();

    @ClassRule
    public static AbstractKeycloakRule keycloakRule = new AbstractKeycloakRule() {
        @Override
        protected void configure(KeycloakSession session, RealmManager manager, RealmModel adminRealm) {
            RealmRepresentation representation = KeycloakServer.loadJson(getClass().getResourceAsStream("/tomcat-test/demorealm.json"), RealmRepresentation.class);
            RealmModel realm = manager.importRealm(representation);
       }
    };

    public static class SendUsernameServlet extends HttpServlet {
        @Override
        protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
            resp.setContentType("text/plain");
            OutputStream stream = resp.getOutputStream();
            stream.write(req.getRequestURL().toString().getBytes());
            stream.write("\n".getBytes());
            Integer count = (Integer)req.getSession().getAttribute("counter");
            if (count == null) count = new Integer(0);
            req.getSession().setAttribute("counter", new Integer(count.intValue() + 1));
            stream.write(count.toString().getBytes());



        }
        @Override
        protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
            doGet(req, resp);
        }
    }

    static Tomcat tomcat = null;

    public static void initTomcat() throws Exception {
        URL dir = ProxyTest.class.getResource("/tomcat-test/webapp/WEB-INF/web.xml");
        File webappDir = new File(dir.getFile()).getParentFile().getParentFile();
        tomcat = new Tomcat();
        String baseDir = getBaseDirectory();
        tomcat.setBaseDir(baseDir);
        tomcat.setPort(8082);

        tomcat.addWebapp("/customer-portal", webappDir.toString());
        System.out.println("configuring app with basedir: " + webappDir.toString());

        tomcat.start();
        //tomcat.getServer().await();
    }

    public static void shutdownTomcat() throws Exception {
        tomcat.stop();
        tomcat.destroy();
    }

    static Undertow proxyServer = null;

    //@BeforeClass
    public static void initProxy() throws Exception {
        initTomcat();
        ProxyServerBuilder builder = new ProxyServerBuilder().addHttpListener(8080, "localhost");
        InputStream is = ProxyTest.class.getResourceAsStream("/keycloak.json");
        AdapterConfig config = KeycloakDeploymentBuilder.loadAdapterConfig(is);

        builder.target("http://localhost:8082")
                .application(config)
                .base("/customer-portal")
                .constraint("/*").add().add();
        proxyServer = builder.build();
        proxyServer.start();

    }

    @AfterClass
    public static void shutdownProxy() throws Exception {
        shutdownTomcat();
        if (proxyServer != null) proxyServer.stop();
    }


    @Rule
    public WebRule webRule = new WebRule(this);
    @WebResource
    protected WebDriver driver;
    @WebResource
    protected LoginPage loginPage;

    public static final String LOGIN_URL = OpenIDConnectService.loginPageUrl(UriBuilder.fromUri("http://localhost:8081/auth")).build("demo").toString();

    @Test
    public void testLoginSSOAndLogout() throws Exception {
        initProxy();
        driver.navigate().to("http://localhost:8080/customer-portal");
        System.out.println("Current url: " + driver.getCurrentUrl());
        Assert.assertTrue(driver.getCurrentUrl().startsWith(LOGIN_URL));
        loginPage.login("bburke@redhat.com", "password");
        System.out.println("Current url: " + driver.getCurrentUrl());
        Assert.assertEquals(driver.getCurrentUrl(), "http://localhost:8080/customer-portal");
        String pageSource = driver.getPageSource();
        System.out.println(pageSource);
        Assert.assertTrue(pageSource.contains("customer-portal"));
        Assert.assertTrue(pageSource.contains("0"));
        driver.navigate().to("http://localhost:8080/customer-portal");
        Assert.assertEquals(driver.getCurrentUrl(), "http://localhost:8080/customer-portal");
        pageSource = driver.getPageSource();
        System.out.println(pageSource);
        Assert.assertTrue(pageSource.contains("customer-portal"));
        Assert.assertTrue(pageSource.contains("1")); // test http session

        // test logout

        String logoutUri = OpenIDConnectService.logoutUrl(UriBuilder.fromUri("http://localhost:8081/auth"))
                .queryParam(OAuth2Constants.REDIRECT_URI, "http://localhost:8080/customer-portal").build("demo").toString();
        driver.navigate().to(logoutUri);
        Assert.assertTrue(driver.getCurrentUrl().startsWith(LOGIN_URL));
        driver.navigate().to("http://localhost:8080/customer-portal");
        String currentUrl = driver.getCurrentUrl();
        Assert.assertTrue(currentUrl.startsWith(LOGIN_URL));


    }

    @Test
    @Ignore
    public void runit() throws Exception {
        Thread.sleep(10000000);
    }


    private static String getBaseDirectory() {
        String dirPath = null;
        String relativeDirPath = "testsuite" + File.separator + "proxy" + File.separator + "target";

        if (System.getProperties().containsKey("maven.home")) {
            dirPath = System.getProperty("user.dir").replaceFirst("testsuite.proxy.*", Matcher.quoteReplacement(relativeDirPath));
        } else {
            for (String c : System.getProperty("java.class.path").split(File.pathSeparator)) {
                if (c.contains(File.separator + "testsuite" + File.separator + "proxy")) {
                    dirPath = c.replaceFirst("testsuite.proxy.*", Matcher.quoteReplacement(relativeDirPath));
                    break;
                }
            }
        }

        String absolutePath = new File(dirPath).getAbsolutePath();
        return absolutePath;
    }




}
