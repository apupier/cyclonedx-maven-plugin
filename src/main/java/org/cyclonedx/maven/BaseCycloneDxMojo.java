/*
 * This file is part of CycloneDX Maven Plugin.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) OWASP Foundation. All Rights Reserved.
 */
package org.cyclonedx.maven;

import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalysis;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.cyclonedx.Version;
import org.cyclonedx.generators.BomGeneratorFactory;
import org.cyclonedx.exception.GeneratorException;
import org.cyclonedx.generators.json.BomJsonGenerator;
import org.cyclonedx.generators.xml.BomXmlGenerator;
import org.cyclonedx.maven.ProjectDependenciesConverter.BomDependencies;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Dependency;
import org.cyclonedx.model.ExternalReference;
import org.cyclonedx.model.LifecycleChoice;
import org.cyclonedx.model.Lifecycles;
import org.cyclonedx.model.Metadata;
import org.cyclonedx.model.Property;
import org.cyclonedx.parsers.JsonParser;
import org.cyclonedx.parsers.Parser;
import org.cyclonedx.parsers.XmlParser;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public abstract class BaseCycloneDxMojo extends AbstractMojo {
    static final String CYCLONEDX_PLUGIN_KEY = "org.cyclonedx:cyclonedx-maven-plugin";
    static final String PROJECT_TYPE = "projectType";

    @Parameter(property = "project", readonly = true, required = true)
    private MavenProject project;

    /**
     * The component type associated to the SBOM metadata. See
     * <a href="https://cyclonedx.org/docs/1.6/json/#metadata_component_type">CycloneDX reference</a> for supported
     * values. 
     *
     * @since 2.0.0
     */
    @Parameter(property = PROJECT_TYPE, defaultValue = "library", required = false)
    private String projectType;

    /**
     * The CycloneDX schema version the BOM will comply with.
     *
     * @since 2.1.0
     */
    @Parameter(property = "schemaVersion", defaultValue = "1.6", required = false)
    private String schemaVersion;
    private Version effectiveSchemaVersion = null;

    /**
     * The CycloneDX output format that should be generated (<code>xml</code>, <code>json</code> or <code>all</code>).
     *
     * @since 2.1.0
     */
    @Parameter(property = "outputFormat", defaultValue = "all", required = false)
    private String outputFormat;

    /**
     * The CycloneDX output file name (without extension) that should be generated (in {@code outputDirectory} directory).
     *
     * @since 2.2.0
     */
    @Parameter(property = "outputName", defaultValue = "bom", required = false)
    private String outputName;

    /**
     * The output directory where to store generated CycloneDX output files.
     *
     * @since 2.7.5
     */
    @Parameter(property = "outputDirectory", defaultValue = "${project.build.directory}", required = false)
    private File outputDirectory;

    /**
     * Should the resulting BOM contain a unique serial number?
     *
     * @since 2.1.0
     */
    @Parameter(property = "includeBomSerialNumber", defaultValue = "true", required = false)
    private boolean includeBomSerialNumber;

    /**
     * Should compile scoped Maven dependencies be included in bom?
     *
     * @since 2.1.0
     */
    @Parameter(property = "includeCompileScope", defaultValue = "true", required = false)
    private boolean includeCompileScope;

    /**
     * Should provided scoped Maven dependencies be included in bom?
     *
     * @since 2.1.0
     */
    @Parameter(property = "includeProvidedScope", defaultValue = "true", required = false)
    private boolean includeProvidedScope;

    /**
     * Should runtime scoped Maven dependencies be included in bom?
     *
     * @since 2.1.0
     */
    @Parameter(property = "includeRuntimeScope", defaultValue = "true", required = false)
    private boolean includeRuntimeScope;

    /**
     * Should test scoped Maven dependencies be included in bom?
     *
     * @since 2.1.0
     */
    @Parameter(property = "includeTestScope", defaultValue = "false", required = false)
    private boolean includeTestScope;

    /**
     * Should system scoped Maven dependencies be included in bom?
     *
     * @since 2.1.0
     */
    @Parameter(property = "includeSystemScope", defaultValue = "true", required = false)
    private boolean includeSystemScope;

    /**
     * Should license text be included in bom?
     *
     * @since 2.1.0
     */
    @Parameter(property = "includeLicenseText", defaultValue = "false", required = false)
    private boolean includeLicenseText;

    /**
     * Excluded types.
     *
     * @since 2.1.0
     */
    @Parameter(property = "excludeTypes", required = false)
    private String[] excludeTypes;

    /**
     * Use the original mechanism for determining whether a component has OPTIONAL or REQUIRED scope,
     * relying on bytecode analysis of the compiled classes instead of the Maven dependency declaration of optional.
     *
     * @since 2.7.9
     */
    @Parameter(property = "detectUnusedForOptionalScope", defaultValue = "false")
    protected boolean detectUnusedForOptionalScope;

    /**
     * Skip CycloneDX execution.
     *
     * @since 1.1.3
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter(property = "cyclonedx.skip", defaultValue = "false", required = false)
    private boolean skip = false;

    /**
     * Don't attach bom.
     *
     * @since 2.1.0
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter(property = "cyclonedx.skipAttach", defaultValue = "false", required = false)
    private boolean skipAttach = false;

    /**
     * Classifier of the attached sbom
     *
     * @since 2.8.1
     */
    @Parameter(property = "cyclonedx.classifier", defaultValue = "cyclonedx")
    private String classifier;

    /**
     * Verbose output.
     *
     * @since 2.6.0
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter(property = "cyclonedx.verbose", defaultValue = "false", required = false)
    private boolean verbose = false;

    /**
     * Timestamp for reproducible output archive entries, either formatted as ISO 8601
     * <code>yyyy-MM-dd'T'HH:mm:ssXXX</code> or as an int representing seconds since the epoch (like
     * <a href="https://reproducible-builds.org/docs/source-date-epoch/">SOURCE_DATE_EPOCH</a>).
     *
     * @since 2.7.9
     */
    @Parameter( defaultValue = "${project.build.outputTimestamp}" )
    private String outputTimestamp;

    /**
     * <a href="https://cyclonedx.org/docs/1.6/json/#metadata_component_externalReferences_items_type">External references</a>
     * to be added to the component the BOM describes <code>$.metadata.component.externalReferences[]</code>:
     * <pre>
     * &lt;externalReferences&gt;
     *   &lt;externalReference&gt;
     *     &lt;type>EXTERNAL_REFERENCE_TYPE&lt;/type&gt;&lt;-- constant id corresponding to "external-reference-type" SBOM type --&gt;
     *     &lt;url>https://...&lt;/url&gt;
     *     &lt;comment>(optional) comment&lt;/comment&gt;
     *   &lt;/externalReference&gt;
     * &lt;/externalReferences&gt;
     * </pre>
     *
     * @see <a href="https://cyclonedx.github.io/cyclonedx-core-java/org/cyclonedx/model/ExternalReference.Type.html">ExternalReference.Type constants</a>
     * @since 2.7.11
     */
    @Parameter
    private ExternalReference[] externalReferences;

    @org.apache.maven.plugins.annotations.Component
    private MavenProjectHelper mavenProjectHelper;

    @org.apache.maven.plugins.annotations.Component
    private ModelConverter modelConverter;

    @org.apache.maven.plugins.annotations.Component
    private ProjectDependenciesConverter projectDependenciesConverter;

    /**
     * Various messages sent to console.
     */
    protected static final String MESSAGE_RESOLVING_DEPS = "CycloneDX: Resolving Dependencies";
    protected static final String MESSAGE_RESOLVING_AGGREGATED_DEPS = "CycloneDX: Resolving Aggregated Dependencies";
    protected static final String MESSAGE_CREATING_BOM = "CycloneDX: Creating BOM version %s with %d component(s)";
    static final String MESSAGE_CALCULATING_HASHES = "CycloneDX: Calculating Hashes";
    protected static final String MESSAGE_WRITING_BOM = "CycloneDX: Writing and validating BOM (%s): %s";
    protected static final String MESSAGE_ATTACHING_BOM = "           attaching as %s-%s-%s.%s";
    protected static final String MESSAGE_VALIDATION_FAILURE = "The BOM does not conform to the CycloneDX BOM standard as defined by the XSD";

    /**
     * Maven plugins that deploy artifacts.
     */
    private static final String MAVEN_DEPLOY_PLUGIN = "org.apache.maven.plugins:maven-deploy-plugin";
    private static final String NEXUS_STAGING_PLUGIN = "org.sonatype.plugins:nexus-staging-maven-plugin";
    private static final String CENTRAL_PUBLISHING_PLUGIN = "org.sonatype.central:central-publishing-maven-plugin";

    /**
     * Returns a reference to the current project.
     *
     * @return returns a reference to the current project
     */
    protected MavenProject getProject() {
        return project;
    }

    protected String generatePackageUrl(final Artifact artifact) {
        return modelConverter.generatePackageUrl(artifact);
    }

    protected Component convertMavenDependency(Artifact artifact) {
        return modelConverter.convertMavenDependency(artifact, schemaVersion(), includeLicenseText);
    }

    /**
     * Analyze the current Maven project to extract the BOM components list and their dependencies.
     *
     * @param topLevelComponents the PURLs for all top level components
     * @param components the components map to fill
     * @param dependencies the dependencies map to fill
     * @return the name of the analysis done to store as a BOM, or {@code null} to not save result.
     * @throws MojoExecutionException something weird happened...
     */
    protected abstract String extractComponentsAndDependencies(Set<String> topLevelComponents, Map<String, Component> components, Map<String, Dependency> dependencies) throws MojoExecutionException;

    /**
     * @return {@literal true} if the execution should be skipped.
     */
    protected boolean shouldSkip() {
        return Boolean.parseBoolean(System.getProperty("cyclonedx.skip", Boolean.toString(skip)));
    }

    protected String getSkipReason() {
        return null;
    }

    public void execute() throws MojoExecutionException {
        if (shouldSkip()) {
            final String skipReason = getSkipReason();
            if (skipReason != null) {
                getLog().info("Skipping CycloneDX goal, because " + skipReason);
            } else {
                getLog().info("Skipping CycloneDX goal");
            }
            return;
        }
        if (!schemaVersion().getVersionString().equals(schemaVersion)) {
            getLog().warn("Invalid schemaVersion configured '" + schemaVersion +"', using " + effectiveSchemaVersion.getVersionString());
            schemaVersion = effectiveSchemaVersion.getVersionString();
        }
        logParameters();

        // top level components do not currently set their scope, we track these to prevent merging of scopes
        final Set<String> topLevelComponents = new LinkedHashSet<>();
        final Map<String, Component> componentMap = new LinkedHashMap<>();
        final Map<String, Dependency> dependencyMap = new LinkedHashMap<>();

        String analysis = extractComponentsAndDependencies(topLevelComponents, componentMap, dependencyMap);
        if (analysis != null) {
            final Metadata metadata = modelConverter.convertMavenProject(project, projectType, schemaVersion(), includeLicenseText, externalReferences);

            if (schemaVersion().getVersion() >= 1.3) {
                metadata.addProperty(newProperty("maven.goal", analysis));

                List<String> scopes = new ArrayList<>();
                if (includeCompileScope) scopes.add("compile");
                if (includeProvidedScope) scopes.add("provided");
                if (includeRuntimeScope) scopes.add("runtime");
                if (includeSystemScope) scopes.add("system");
                if (includeTestScope) scopes.add("test");
                metadata.addProperty(newProperty("maven.scopes", String.join(",", scopes)));

                if (detectUnusedForOptionalScope) {
                    metadata.addProperty(newProperty("maven.optional.unused", Boolean.toString(detectUnusedForOptionalScope)));
                }
            }

            final Component rootComponent = metadata.getComponent();
            componentMap.remove(rootComponent.getPurl());

            projectDependenciesConverter.cleanupBomDependencies(metadata, componentMap, dependencyMap);

            generateBom(analysis, metadata, new ArrayList<>(componentMap.values()), new ArrayList<>(dependencyMap.values()));
        }
    }

    private Property newProperty(String name, String value) {
        Property property = new Property();
        property.setName(name);
        property.setValue(value);
        return property;
    }

    private void generateBom(String analysis, Metadata metadata, List<Component> components, List<Dependency> dependencies) throws MojoExecutionException {
        try {
            getLog().info(String.format(MESSAGE_CREATING_BOM, schemaVersion, components.size()));
            final Bom bom = new Bom();
            bom.setComponents(components);

            if (outputTimestamp != null) {
                // activate Reproducible Builds mode
                metadata.setTimestamp(null);
                if (schemaVersion().getVersion() >= 1.3) {
                    metadata.addProperty(newProperty("cdx:reproducible", "enabled"));
                }
            }

            if (schemaVersion().getVersion() >= 1.1 && includeBomSerialNumber) {
                String serialNumber = generateSerialNumber(metadata.getProperties());
                bom.setSerialNumber(serialNumber);
            }

            if (schemaVersion().getVersion() >= 1.2) {
                bom.setMetadata(metadata);
                bom.setDependencies(dependencies);
            }

            /*if (schemaVersion().getVersion() >= 1.3) {
                if (excludeArtifactId != null && excludeTypes.length > 0) { // TODO
                    final Composition composition = new Composition();
                    composition.setAggregate(Composition.Aggregate.INCOMPLETE);
                    composition.setDependencies(Collections.singletonList(new Dependency(bom.getMetadata().getComponent().getBomRef())));
                    bom.setCompositions(Collections.singletonList(composition));
                }
            }*/

            if (schemaVersion().getVersion() >= 1.5) {
                LifecycleChoice build = new LifecycleChoice();
                build.setPhase(LifecycleChoice.Phase.BUILD);
                Lifecycles lifecycles = new Lifecycles();
                lifecycles.setLifecycleChoice(Collections.singletonList(build));
                metadata.setLifecycles(lifecycles);
            }

            if ("all".equalsIgnoreCase(outputFormat)
                    || "xml".equalsIgnoreCase(outputFormat)
                    || "json".equalsIgnoreCase(outputFormat)) {
                saveBom(bom);
            } else {
                getLog().error("Unsupported output format. Valid options are XML and JSON");
            }
        } catch (GeneratorException | ParserConfigurationException | IOException e) {
            throw new MojoExecutionException("An error occurred executing " + this.getClass().getName() + ": " + e.getMessage(), e);
        }
    }

    private String generateSerialNumber(List<Property> properties) {
        String gav = String.format("%s:%s:%s", project.getGroupId(), project.getArtifactId(), project.getVersion());
        StringBuilder sb = new StringBuilder(gav);
        if (properties != null) {
            for(Property prop: properties) {
                sb.append(';');
                sb.append(prop.getName());
                sb.append('=');
                sb.append(prop.getValue());
            }
        }
        UUID uuid = UUID.nameUUIDFromBytes(sb.toString().getBytes(StandardCharsets.UTF_8));
        return String.format("urn:uuid:%s", uuid);
    }

    private void saveBom(Bom bom) throws ParserConfigurationException, IOException, GeneratorException,
            MojoExecutionException {
        if ("all".equalsIgnoreCase(outputFormat) || "xml".equalsIgnoreCase(outputFormat)) {
            final BomXmlGenerator bomGenerator = BomGeneratorFactory.createXml(schemaVersion(), bom);
            //bomGenerator.generate();

            final String bomString = bomGenerator.toXmlString();
            saveBomToFile(bomString, "xml", new XmlParser());
        }
        if ("all".equalsIgnoreCase(outputFormat) || "json".equalsIgnoreCase(outputFormat)) {
            final BomJsonGenerator bomGenerator = BomGeneratorFactory.createJson(schemaVersion(), bom);

            final String bomString = bomGenerator.toJsonString();
            saveBomToFile(bomString, "json", new JsonParser());
        }
    }

    private void saveBomToFile(String bomString, String extension, Parser bomParser) throws IOException, MojoExecutionException {
        final File bomFile = new File(outputDirectory, outputName + "." + extension);

        getLog().info(String.format(MESSAGE_WRITING_BOM, extension.toUpperCase(), bomFile.getAbsolutePath()));
        FileUtils.write(bomFile, bomString, StandardCharsets.UTF_8, false);

        if (!bomParser.isValid(bomFile, schemaVersion())) {
            throw new MojoExecutionException(MESSAGE_VALIDATION_FAILURE);
        }

        if (!skipAttach) {
            getLog().info(String.format(MESSAGE_ATTACHING_BOM, project.getArtifactId(), project.getVersion(), classifier, extension));
            mavenProjectHelper.attachArtifact(project, extension, classifier, bomFile);
        }
    }

    protected BomDependencies extractBOMDependencies(MavenProject mavenProject) throws MojoExecutionException {
        ProjectDependenciesConverter.MavenDependencyScopes include = new ProjectDependenciesConverter.MavenDependencyScopes(includeCompileScope, includeProvidedScope, includeRuntimeScope, includeTestScope, includeSystemScope);
        return projectDependenciesConverter.extractBOMDependencies(mavenProject, include, excludeTypes);
    }

    /**
     * Resolves the CycloneDX schema the mojo has been requested to use.
     * @return the CycloneDX schema to use
     */
    protected Version schemaVersion() {
        if (effectiveSchemaVersion == null) {
            if ("1.0".equals(schemaVersion)) {
                effectiveSchemaVersion = Version.VERSION_10;
            } else if ("1.1".equals(schemaVersion)) {
                effectiveSchemaVersion = Version.VERSION_11;
            } else if ("1.2".equals(schemaVersion)) {
                effectiveSchemaVersion = Version.VERSION_12;
            } else if ("1.3".equals(schemaVersion)) {
                effectiveSchemaVersion = Version.VERSION_13;
            } else if ("1.4".equals(schemaVersion)) {
                effectiveSchemaVersion = Version.VERSION_14;
            } else if ("1.5".equals(schemaVersion)) {
                effectiveSchemaVersion = Version.VERSION_15;
            } else {
                effectiveSchemaVersion = Version.VERSION_16;
            }
        }
        return effectiveSchemaVersion;
    }

    protected void logAdditionalParameters() {
        // no additional parameters
    }

    protected void logParameters() {
        if (verbose && getLog().isInfoEnabled()) {
            getLog().info("CycloneDX: Parameters");
            getLog().info("------------------------------------------------------------------------");
            getLog().info("schemaVersion          : " + schemaVersion().getVersionString());
            getLog().info("includeBomSerialNumber : " + includeBomSerialNumber);
            getLog().info("includeCompileScope    : " + includeCompileScope);
            getLog().info("includeProvidedScope   : " + includeProvidedScope);
            getLog().info("includeRuntimeScope    : " + includeRuntimeScope);
            getLog().info("includeTestScope       : " + includeTestScope);
            getLog().info("includeSystemScope     : " + includeSystemScope);
            getLog().info("includeLicenseText     : " + includeLicenseText);
            getLog().info("outputFormat           : " + outputFormat);
            getLog().info("outputName             : " + outputName);
            logAdditionalParameters();
            getLog().info("------------------------------------------------------------------------");
        }
    }

    protected void populateComponents(final Set<String> topLevelComponents, final Map<String, Component> components, final Map<String, Artifact> artifacts, final ProjectDependencyAnalysis dependencyAnalysis) {
        for (Map.Entry<String, Artifact> entry: artifacts.entrySet()) {
            final String purl = entry.getKey();
            final Artifact artifact = entry.getValue();
            final Component.Scope artifactScope = getComponentScope(artifact, dependencyAnalysis);
            final Component component = components.get(purl);
            if (component == null) {
                final Component newComponent = convertMavenDependency(artifact);
                newComponent.setScope(artifactScope);
                components.put(purl, newComponent);
            } else if (!topLevelComponents.contains(purl)) {
                component.setScope(mergeScopes(component.getScope(), artifactScope));
            }
        }
    }

    /**
     * Get the BOM component scope (required/optional/excluded).  The scope can either be determined through bytecode
     * analysis or through maven dependency resolution.
     *
     * @param artifact Artifact from maven project
     * @param projectDependencyAnalysis Maven Project Dependency Analysis data
     *
     * @return Component.Scope - REQUIRED, OPTIONAL or null if it cannot be determined
     *
     * @see #detectUnusedForOptionalScope
     */
    private Component.Scope getComponentScope(Artifact artifact, ProjectDependencyAnalysis projectDependencyAnalysis) {
        if (detectUnusedForOptionalScope) {
            return inferComponentScope(artifact, projectDependencyAnalysis);
        } else {
            return (artifact.isOptional() ? Component.Scope.OPTIONAL : Component.Scope.REQUIRED);
        }
    }

    /**
     * Infer BOM component scope (required/optional/excluded) based on Maven project dependency analysis.
     *
     * @param artifact Artifact from maven project
     * @param projectDependencyAnalysis Maven Project Dependency Analysis data
     *
     * @return Component.Scope - REQUIRED: If the component is used (as detected by project dependency analysis). OPTIONAL: If it is unused
     */
    private Component.Scope inferComponentScope(Artifact artifact, ProjectDependencyAnalysis projectDependencyAnalysis) {
        if (projectDependencyAnalysis == null) {
            return null;
        }

        Set<Artifact> usedDeclaredArtifacts = projectDependencyAnalysis.getUsedDeclaredArtifacts();
        Set<Artifact> usedUndeclaredArtifacts = projectDependencyAnalysis.getUsedUndeclaredArtifacts();
        Set<Artifact> unusedDeclaredArtifacts = projectDependencyAnalysis.getUnusedDeclaredArtifacts();
        Set<Artifact> testArtifactsWithNonTestScope = projectDependencyAnalysis.getTestArtifactsWithNonTestScope();

        // Is the artifact used?
        if (usedDeclaredArtifacts.contains(artifact) || usedUndeclaredArtifacts.contains(artifact)) {
            return Component.Scope.REQUIRED;
        }

        // Is the artifact unused or test?
        if (unusedDeclaredArtifacts.contains(artifact) || testArtifactsWithNonTestScope.contains(artifact)) {
            return Component.Scope.OPTIONAL;
        }

        return null;
    }

    // Merging of scopes follows the method previously implemented in the aggregate code.  This needs to be fixed in a future PR.
    private Component.Scope mergeScopes(final Component.Scope existing, final Component.Scope project) {
        if ((Component.Scope.REQUIRED == existing) || (Component.Scope.REQUIRED == project)) {
            return Component.Scope.REQUIRED;
        }
        if (null == existing) {
            return project;
        }
        return existing;
    }

    static boolean isDeployable(final MavenProject project) {
        return isDeployable(project,
                        MAVEN_DEPLOY_PLUGIN,
                        "skip",
                        "maven.deploy.skip")
                || isDeployable(project,
                        NEXUS_STAGING_PLUGIN,
                        "skipNexusStagingDeployMojo",
                        "skipNexusStagingDeployMojo")
                || isDeployable(project,
                        CENTRAL_PUBLISHING_PLUGIN,
                        "skipPublishing",
                        "skipPublishing");
    }

    private static boolean isDeployable(final MavenProject project,
                                        final String pluginKey,
                                        final String parameter,
                                        final String propertyName) {
        final Plugin plugin = project.getPlugin(pluginKey);
        if (plugin != null) {
            // Default skip value
            final String property = System.getProperty(propertyName, project.getProperties().getProperty(propertyName));
            final boolean defaultSkipValue = property != null ? Boolean.parseBoolean(property) : false;
            // Find an execution that is not skipped
            for (final PluginExecution execution : plugin.getExecutions()) {
                if (execution.getGoals().contains("deploy")) {
                    final Xpp3Dom executionConf = (Xpp3Dom) execution.getConfiguration();
                    final Xpp3Dom target = (executionConf == null) ? null : executionConf.getChild(parameter);
                    final boolean skipValue = (target == null) ? defaultSkipValue : Boolean.parseBoolean(target.getValue());
                    if (!skipValue) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
