/*
 * Copyright 2020, Seqera Labs
 * Copyright 2013-2019, Centre for Genomic Regulation (CRG)
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
 */

package nextflow.script.bundle

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.file.FileHelper
import nextflow.util.CacheHelper
import nextflow.util.MemoryUnit

/**
 * Model a module bundle
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@Canonical
@CompileStatic
class ModuleBundle {

    public static MemoryUnit MAX_FILE_SIZE = MemoryUnit.of('1MB')
    public static MemoryUnit MAX_BUNDLE_SIZE = MemoryUnit.of('5MB')

    private Path root
    private Map<String,Path> content = new LinkedHashMap<>(100)
    private Path dockerfile
    private MemoryUnit maxFileSize = MAX_FILE_SIZE
    private MemoryUnit maxBundleSize = MAX_BUNDLE_SIZE

    ModuleBundle(Path root) {
        this.root = root
        this.dockerfile = dockefile0(root.resolveSibling('Dockerfile'))
    }

    ModuleBundle withMaxFileSize(MemoryUnit mem) {
        this.maxFileSize = mem
        return this
    }

    ModuleBundle withBundleSize(MemoryUnit mem) {
        this.maxBundleSize = mem
        return this
    }

    Path getRoot() { root }

    static private Path dockefile0(Path path) {
        return path?.exists() ? path : null
    }

    ModuleBundle withPaths(Collection<Path> paths) {
        this.content = new LinkedHashMap<String,Path>(100)
        long totSize = 0
        for( Path it : paths ) {
            final attrs = Files.readAttributes(it, BasicFileAttributes, LinkOption.NOFOLLOW_LINKS)
            if( attrs.size()>maxFileSize.bytes )
                throw new IllegalArgumentException("Module file size cannot be bigger than $maxFileSize - offending file: $it")
            if( !attrs.isDirectory() && !attrs.isRegularFile() )
                throw new IllegalArgumentException("Module bundle does not allow link files - offending file: $it")
            if( attrs.isRegularFile() ) {
                totSize += attrs.size()
                if( totSize>maxBundleSize.bytes )
                throw new IllegalArgumentException("Module total size cannot exceed $maxBundleSize")
            }

            final name = root.relativize(it).toString()
            content.put(name, it)
        }
        return this
    }

    Path getDockerfile() {
        return dockerfile
    }

    Set<Path> getPaths() {
        return new HashSet<Path>(content.values())
    }

    List<Path> getPathsList() {
        final result = new ArrayList<Path>(content.size())
        for( String name : getEntries() )
            result.add(path(name))
        return result
    }

    Path path(String name) {
        return content.get(name)
    }

    Set<String> getEntries() {
        return new TreeSet<String>(content.keySet())
    }

    boolean hasEntries() {
        return content.size()
    }

    boolean asBoolean() {
        return content.size() || dockerfile
    }

    /**
     * Creates a {@link ModuleBundle} object populated with the set of files in the root directory
     *
     * @param bundleRoot
     *      The bundle root path
     * @return
     *      An instance of {@link ModuleBundle} holding the set of files that are container
     *      in the bundle directory
     */
    static ModuleBundle scan(Path bundleRoot, Map config=[:]) {
        final result = new ModuleBundle(bundleRoot)
        if( !bundleRoot.exists() )
            return result
        if( !bundleRoot.isDirectory() ) {
            log.warn "Module bundle location is not a directory path: '$bundleRoot'"
            return result
        }
        // setup config
        if( config.maxFileSize )
            result.maxFileSize = config.maxFileSize as MemoryUnit
        if( config.maxBundleSize )
            result.maxBundleSize = config.maxBundleSize as MemoryUnit
        // load bundle files
        final files = new HashSet(10)
        final opts = [type: 'any', hidden: true, relative: false]
        FileHelper.visitFiles(opts, bundleRoot, '**') { files.add(it) }
        result.withPaths(files)
        return result
    }

    private List fileMeta(String name, Path file) {
        final attrs = Files.readAttributes(file, BasicFileAttributes)
        final meta = [
                name,
                attrs.isRegularFile() ? attrs.size() : 0,
                attrs.lastModifiedTime().toMillis(),
                Integer.toOctalString(file.getPermissionsMode()) ]
        log.trace "Module bundle entry=$meta"
        return meta
    }

    String fingerprint() {
        final allMeta = new ArrayList()
        for( String name : getEntries() ) {
            final file = this.path(name)
            allMeta.add(fileMeta(name,file))
        }
        if( dockerfile ) {
            allMeta.add(fileMeta(dockerfile.name, dockerfile))
        }

        return CacheHelper.hasher(allMeta).hash().toString()
    }

}
