package com.peregrine.sitemap.impl;

/*-
 * #%L
 * platform base - Core
 * %%
 * Copyright (C) 2017 headwire inc.
 * %%
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * #L%
 */

import com.peregrine.sitemap.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.*;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import static com.peregrine.commons.util.PerConstants.SLASH;
import static com.peregrine.commons.util.PerConstants.SLING_FOLDER;

@Component(service = SiteMapCache.class)
@Designate(ocd = SiteMapCacheImplConfig.class)
public final class SiteMapCacheImpl implements SiteMapCache {

    private static final String COULD_NOT_GET_SERVICE_RESOURCE_RESOLVER = "Could not get Service Resource Resolver.";
    private static final String COULD_NOT_SAVE_SITE_MAP_CACHE = "Could not save Site Map Cache.";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Reference
    private SiteMapExtractorsContainer siteMapExtractorsContainer;

    @Reference
    private SiteMapBuilder siteMapBuilder;

    @Reference
    private ResourceResolverFactoryProxy resourceResolverFactory;

    @Reference
    private SiteMapUrlBuilder siteMapUrlBuilder;

    private int maxEntriesCount;
    private int maxFileSize;
    private String location;

    @Activate
    public void activate(final SiteMapCacheImplConfig config) {
        maxEntriesCount = config.maxEntriesCount();
        if (maxEntriesCount <= 0) {
            maxEntriesCount = Integer.MAX_VALUE;
        }

        maxFileSize = config.maxFileSize();
        if (maxFileSize <= 0) {
            maxFileSize = Integer.MAX_VALUE;
        }

        location = config.location();
    }

    @Override
    public String get(final Resource rootPage, final int index) {
        try (final ResourceResolver resourceResolver = getServiceResourceResolver()) {
            final String cachePath = getCachePath(rootPage);
            Resource cache = resourceResolver.getResource(cachePath);
            if (cache == null) {
                cache = saveCache(resourceResolver, rootPage);
                if (cache == null) {
                    return null;
                }
            }

            final ValueMap properties = cache.getValueMap();
            final String key = Integer.toString(index);
            return properties.get(key, String.class);
        } catch (final LoginException e) {
            logger.error(COULD_NOT_GET_SERVICE_RESOURCE_RESOLVER, e);
            return null;
        }
    }

    private ResourceResolver getServiceResourceResolver() throws LoginException {
        return resourceResolverFactory.getServiceResourceResolver();
    }

    private String getCachePath(final Resource rootPage) {
        return location + rootPage.getPath();
    }

    private Resource saveCache(final ResourceResolver resourceResolver, final Resource rootPage) {
        try {
            final Resource cache = getOrCreateCacheResource(resourceResolver, getCachePath(rootPage));
            final SiteMapExtractor extractor = siteMapExtractorsContainer.findFirstFor(rootPage);
            if (extractor == null) {
                return null;
            }

            final Collection<SiteMapEntry> entries = extractor.extract(rootPage);
            final LinkedList<List<SiteMapEntry>> splitEntries = splitEntries(entries);
            final ArrayList<String> strings = new ArrayList<>();
            final int numberOfParts = splitEntries.size();
            if (numberOfParts > 1) {
                strings.add(siteMapBuilder.buildSiteMapIndex(rootPage, extractor, numberOfParts));
            }

            for (final List<SiteMapEntry> list : splitEntries) {
                strings.add(siteMapBuilder.buildUrlSet(list));
            }

            final ModifiableValueMap modifiableValueMap = cache.adaptTo(ModifiableValueMap.class);
            final int stringsSize = strings.size();
            for (int i = 0; i < stringsSize; i++) {
                modifiableValueMap.put(Integer.toString(i), strings.get(i));
            }

            removeCachedItems(modifiableValueMap, stringsSize);

            resourceResolver.commit();
            return cache;
        } catch (final RepositoryException | PersistenceException e) {
            logger.error(COULD_NOT_SAVE_SITE_MAP_CACHE, e);
            return null;
        }
    }

    private Resource getOrCreateCacheResource(final ResourceResolver resourceResolver, final String path) throws RepositoryException {
        final Resource resource = Utils.getFirstExistingAncestorOnPath(resourceResolver, path);
        final String missingPath;
        if (resource == null) {
            missingPath = path;
        } else {
            missingPath = StringUtils.substringAfter(path, resource.getPath());
        }

        final String[] missing = StringUtils.split(missingPath, SLASH);
        Node node = resource.adaptTo(Node.class);
        for (final String name : missing) {
            node = node.addNode(name, SLING_FOLDER);
        }

        node.getSession().save();
        return resourceResolver.getResource(path);
    }

    private LinkedList<List<SiteMapEntry>> splitEntries(final Collection<SiteMapEntry> entries) {
        final LinkedList<List<SiteMapEntry>> result = new LinkedList<>();
        int index = 0;
        int size = siteMapBuilder.getBaseSiteMapLength();
        List<SiteMapEntry> split = new LinkedList<>();
        result.add(split);
        for (final SiteMapEntry entry : entries) {
            final int entrySize = siteMapBuilder.getSize(entry);
            if (index < maxEntriesCount && size + entrySize <= maxFileSize) {
                split.add(entry);
                index++;
                size += entrySize;
            } else {
                index = 0;
                size = siteMapBuilder.getBaseSiteMapLength();
                split = new LinkedList<>();
                result.add(split);
            }
        }

        return result;
    }

    private void removeCachedItems(final ModifiableValueMap modifiableValueMap, final int startItemIndex) {
        int i = startItemIndex;
        String key = Integer.toString(i);
        for (; modifiableValueMap.containsKey(key); i++) {
            modifiableValueMap.remove(key);
            key = Integer.toString(i);
        }
    }

    @Override
    public void rebuild(final String rootPagePath) {
        try (final ResourceResolver resourceResolver = getServiceResourceResolver()) {
            saveCache(resourceResolver, rootPagePath);
        } catch (final LoginException e) {
            logger.error(COULD_NOT_GET_SERVICE_RESOURCE_RESOLVER, e);
        }
    }

    private Resource saveCache(final ResourceResolver resourceResolver, final String rootPagePath) {
        return saveCache(resourceResolver, resourceResolver.getResource(rootPagePath));
    }
}
