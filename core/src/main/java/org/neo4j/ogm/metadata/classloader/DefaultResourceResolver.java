/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with
 * separate copyright notices and license terms. Your use of the source
 * code for these subcomponents is subject to the terms and
 *  conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package org.neo4j.ogm.metadata.classloader;


import java.io.File;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;

/**
 * @author vince
 */
@Deprecated
public class DefaultResourceResolver implements ResourceResolver
{

    @Override
    public File resolve( final URL resource ) throws URISyntaxException, MalformedURLException
    {
        if(resource.getProtocol().equals("file")) {
            return new File(resource.toURI());
        }

        if(resource.getProtocol().equals("jar")) {
            String jarFileURL = resource.getPath().substring(0, resource.getPath().indexOf("!"));  //Strip out the jar protocol
            return resolve(new URL(jarFileURL));
        }

        return null; // not handled
    }
}
