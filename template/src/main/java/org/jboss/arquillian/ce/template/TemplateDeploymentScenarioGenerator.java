/*
 * JBoss, Home of Professional Open Source
 * Copyright 2015 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
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

package org.jboss.arquillian.ce.template;

import java.util.Collections;
import java.util.List;

import org.jboss.arquillian.ce.api.GitDeployment;
import org.jboss.arquillian.container.spi.client.deployment.DeploymentDescription;
import org.jboss.arquillian.container.test.spi.client.deployment.DeploymentScenarioGenerator;
import org.jboss.arquillian.test.spi.TestClass;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;

/**
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class TemplateDeploymentScenarioGenerator implements DeploymentScenarioGenerator {
    private final static String DELEGATE_CLASS = "org.jboss.arquillian.container.test.impl.client.deployment.AnnotationDeploymentScenarioGenerator";

    private final static String WEB_XML =
        "<web-app version=\"3.0\"\n" +
            "         xmlns=\"http://java.sun.com/xml/ns/javaee\"\n" +
            "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
            "         xsi:schemaLocation=\"http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd\"\n" +
            "         metadata-complete=\"false\">\n" +
            "</web-app>";

    public List<DeploymentDescription> generate(TestClass testClass) {
        if (testClass.isAnnotationPresent(GitDeployment.class)) {
            return Collections.singletonList(generateDummyDeployment());
        } else {
            try {
                DeploymentScenarioGenerator delegate = (DeploymentScenarioGenerator) Class.forName(DELEGATE_CLASS).newInstance();
                return delegate.generate(testClass);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private DeploymentDescription generateDummyDeployment() {
        WebArchive archive = ShrinkWrap.create(WebArchive.class, "ROOT.war");
        archive.setWebXML(new StringAsset(WEB_XML));
        return new DeploymentDescription("_DEFAULT_", archive);
    }
}