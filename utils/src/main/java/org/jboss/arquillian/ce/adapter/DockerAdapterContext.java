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

package org.jboss.arquillian.ce.adapter;

import java.io.InputStream;
import java.util.Properties;

import org.jboss.arquillian.ce.utils.DockerFileTemplateHandler;
import org.jboss.shrinkwrap.api.Archive;

/**
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class DockerAdapterContext {
    private DockerFileTemplateHandler handler;
    private InputStream dockerfileTemplate;
    private Archive deployment;
    private Properties properties;
    private String imageNamePrefix;

    public DockerAdapterContext(DockerFileTemplateHandler handler, InputStream dockerfileTemplate, Archive deployment, Properties properties, String imageNamePrefix) {
        this.handler = handler;
        this.dockerfileTemplate = dockerfileTemplate;
        this.deployment = deployment;
        this.properties = properties;
        this.imageNamePrefix = imageNamePrefix;
    }

    public DockerFileTemplateHandler getHandler() {
        return handler;
    }

    public InputStream getDockerfileTemplate() {
        return dockerfileTemplate;
    }

    public Archive getDeployment() {
        return deployment;
    }

    public Properties getProperties() {
        return properties;
    }

    public String getImageNamePrefix() {
        return imageNamePrefix;
    }
}
