package org.bukkit.plugin.java.annotation;

import cn.apisium.papershelled.annotation.AnnotationProcessor;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.annotation.command.Command;
import org.bukkit.plugin.java.annotation.command.Commands;
import org.bukkit.plugin.java.annotation.dependency.Dependency;
import org.bukkit.plugin.java.annotation.dependency.LoadBefore;
import org.bukkit.plugin.java.annotation.dependency.SoftDependency;
import org.bukkit.plugin.java.annotation.permission.ChildPermission;
import org.bukkit.plugin.java.annotation.permission.Permission;
import org.bukkit.plugin.java.annotation.permission.Permissions;
import org.bukkit.plugin.java.annotation.plugin.ApiVersion;
import org.bukkit.plugin.java.annotation.plugin.Description;
import org.bukkit.plugin.java.annotation.dependency.Library;
import org.bukkit.plugin.java.annotation.plugin.LoadOrder;
import org.bukkit.plugin.java.annotation.plugin.LogPrefix;
import org.bukkit.plugin.java.annotation.plugin.Plugin;
import org.bukkit.plugin.java.annotation.plugin.Website;
import org.bukkit.plugin.java.annotation.plugin.author.Author;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Tag;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@SupportedOptions("refMapFileName")
@SupportedAnnotationTypes({"cn.apisium.papershelled.annotation.*", "org.bukkit.plugin.java.annotation.*"})
@SupportedSourceVersion( SourceVersion.RELEASE_8 )
public class PluginAnnotationProcessor extends AbstractProcessor {

    private static final DateTimeFormatter dFormat = DateTimeFormatter.ofPattern( "yyyy/MM/dd HH:mm:ss", Locale.ENGLISH );

    @Override
    public boolean process(Set<? extends TypeElement> annots, RoundEnvironment rEnv) {
        Element mainPluginElement;

        Set<? extends Element> elements = rEnv.getElementsAnnotatedWith( Plugin.class );
        if ( elements.size() > 1 ) {
            raiseError( "Found more than one plugin main class" );
            return false;
        }

        if ( elements.isEmpty() ) { // don't raise error because we don't know which run this is
            return false;
        }
        mainPluginElement = elements.iterator().next();

        TypeElement mainPluginType;
        if ( mainPluginElement instanceof TypeElement ) {
            mainPluginType = ( TypeElement ) mainPluginElement;
        } else {
            raiseError( "Main plugin class is not a class", mainPluginElement );
            return false;
        }

        if ( !( mainPluginType.getEnclosingElement() instanceof PackageElement ) ) {
            raiseError( "Main plugin class is not a top-level class", mainPluginType );
            return false;
        }

        if ( mainPluginType.getModifiers().contains( Modifier.STATIC ) ) {
            raiseError( "Main plugin class cannot be static nested", mainPluginType );
            return false;
        }

        if ( mainPluginType.getModifiers().contains( Modifier.ABSTRACT ) ) {
            raiseError( "Main plugin class cannot be abstract", mainPluginType );
            return false;
        }

        Map<String, Object> yml = new LinkedHashMap<>(); // linked so we can maintain the same output into file for sanity

        // populate mainName
        final String mainName = mainPluginType.getQualifiedName().toString();
        yml.put( "main", mainName ); // always override this so we make sure the main class name is correct

        // populate plugin name
        processAndPut( yml, "name", mainPluginType, mainName.substring( mainName.lastIndexOf( '.' ) + 1 ), Plugin.class, String.class, "name" );

        // populate version
        processAndPut( yml, "version", mainPluginType, Plugin.DEFAULT_VERSION, Plugin.class, String.class, "version" );

        // populate plugin description
        processAndPut( yml, "description", mainPluginType, null, Description.class, String.class );

        // populate plugin load order
        processAndPut( yml, "load", mainPluginType, null, LoadOrder.class, String.class );

        // authors
        Author[] authors = mainPluginType.getAnnotationsByType( Author.class );
        List<String> authorMap = new ArrayList<>();
        for ( Author auth : authors ) {
            authorMap.add( auth.value() );
        }
        if ( authorMap.size() > 1 ) {
            yml.put( "authors", authorMap );
        } else if ( authorMap.size() == 1 ) {
            yml.put( "author", authorMap.iterator().next() );
        }

        // website
        processAndPut( yml, "website", mainPluginType, null, Website.class, String.class );

        // prefix
        processAndPut( yml, "prefix", mainPluginType, null, LogPrefix.class, String.class );

        // libraries
        Library[] libraries = mainPluginType.getAnnotationsByType( Library.class );
        List<String> libraryArr = new ArrayList<>();
        for ( Library lib : libraries ) {
            libraryArr.add( lib.value() );
        }
        if ( !libraryArr.isEmpty() ) yml.put( "libraries", libraryArr );

        // dependencies
        Dependency[] dependencies = mainPluginType.getAnnotationsByType( Dependency.class );
        List<String> hardDependencies = new ArrayList<>();
        for ( Dependency dep : dependencies ) {
            hardDependencies.add( dep.value() );
        }
        if ( !hardDependencies.isEmpty() ) yml.put( "depend", hardDependencies );

        // soft-dependencies
        SoftDependency[] softDependencies = mainPluginType.getAnnotationsByType( SoftDependency.class );
        String[] softDepArr = new String[ softDependencies.length ];
        for ( int i = 0; i < softDependencies.length; i++ ) {
            softDepArr[ i ] = softDependencies[ i ].value();
        }
        if ( softDepArr.length > 0 ) yml.put( "softdepend", softDepArr );

        // load-before
        LoadBefore[] loadBefore = mainPluginType.getAnnotationsByType( LoadBefore.class );
        String[] loadBeforeArr = new String[ loadBefore.length ];
        for ( int i = 0; i < loadBefore.length; i++ ) {
            loadBeforeArr[ i ] = loadBefore[ i ].value();
        }
        if ( loadBeforeArr.length > 0 ) yml.put( "loadbefore", loadBeforeArr );

        // commands
        // Begin processing external command annotations
        Map<String, Map<String, Object>> commandMap = new LinkedHashMap<>();
        boolean validCommandExecutors = processExternalCommands( rEnv.getElementsAnnotatedWith( Commands.class ), mainPluginType, commandMap );
        if ( !validCommandExecutors ) {
            // #processExternalCommand already raised the errors
            return false;
        }

        Commands commands = mainPluginType.getAnnotation( Commands.class );

        // Check main class for any command annotations
        if ( commands != null ) {
            Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
            merged.putAll( commandMap );
            merged.putAll( this.processCommands( commands ) );
            commandMap = merged;
        }

        yml.put( "commands", commandMap );

        // Permissions
        Map<String, Map<String, Object>> permissionMetadata = new LinkedHashMap<>();

        Set<? extends Element> permissionAnnotations = rEnv.getElementsAnnotatedWith( Command.class );
        if ( permissionAnnotations.size() > 0 ) {
            for ( Element element : permissionAnnotations ) {
                if ( element.equals( mainPluginElement ) ) {
                    continue;
                }
                if ( element.getAnnotation( Permission.class ) != null ) {
                    Permission permissionAnnotation = element.getAnnotation( Permission.class );
                    permissionMetadata.put( permissionAnnotation.name(), this.processPermission( permissionAnnotation ) );
                }
            }
        }

        Permissions permissions = mainPluginType.getAnnotation( Permissions.class );
        if ( permissions != null ) {
            Map<String, Map<String, Object>> joined = new LinkedHashMap<>();
            joined.putAll( permissionMetadata );
            joined.putAll( this.processPermissions( permissions ) );
            permissionMetadata = joined;
        }

        // Process Permissions on command executors
        boolean validPermissions = processExternalPermissions( rEnv.getElementsAnnotatedWith( Permissions.class ), mainPluginType, permissionMetadata );
        if ( !validPermissions ) {
            return false;
        }
        yml.put( "permissions", permissionMetadata );

        // api-version
        if ( mainPluginType.getAnnotation( ApiVersion.class ) != null ) {
            ApiVersion apiVersion = mainPluginType.getAnnotation( ApiVersion.class );
            if ( apiVersion.value() != ApiVersion.Target.DEFAULT ) {
                yml.put( "api-version", apiVersion.value().getVersion() );
            }
        }

        try {
            AnnotationProcessor.process( mainPluginType, this.processingEnv );
            Yaml yaml = new Yaml();
            FileObject file = this.processingEnv.getFiler().createResource( StandardLocation.CLASS_OUTPUT, "", "plugin.yml" );
            try ( Writer w = file.openWriter() ) {
                w.append( "# Auto-generated plugin.yml, generated at " )
                 .append( LocalDateTime.now().format( dFormat ) )
                 .append( " by " )
                 .append( this.getClass().getName() )
                 .append( "\n\n" );
                // have to format the yaml explicitly because otherwise it dumps child nodes as maps within braces.
                String raw = yaml.dumpAs( yml, Tag.MAP, DumperOptions.FlowStyle.BLOCK );
                w.write( raw );
                w.flush();
            }
            // try with resources will close the Writer since it implements Closeable
        } catch ( IOException e ) {
            throw new RuntimeException( e );
        }
        return true;
    }

    private boolean processExternalPermissions(Set<? extends Element> commandExecutors, TypeElement mainPluginType, Map<String, Map<String, Object>> yml) {
        for ( Element element : commandExecutors ) {
            // Check to see if someone annotated a non-class with this
            if ( !( element instanceof TypeElement ) ) {
                this.raiseError( "Specified Command Executor class is not a class." );
                return false;
            }

            TypeElement typeElement = ( TypeElement ) element;
            if ( typeElement.equals( mainPluginType ) ) {
                continue;
            }

            Map<String, Map<String, Object>> newMap = new LinkedHashMap<>();
            Permissions annotation = typeElement.getAnnotation( Permissions.class );
            if ( annotation != null && annotation.value().length > 0 ) {
                newMap.putAll( processPermissions( annotation ) );
            }
            yml.putAll( newMap );
        }
        return true;
    }

    private void raiseError(String message) {
        this.processingEnv.getMessager().printMessage( Diagnostic.Kind.ERROR, message );
    }

    private void raiseError(String message, Element element) {
        this.processingEnv.getMessager().printMessage( Diagnostic.Kind.ERROR, message, element );
    }

    @SuppressWarnings({"UnusedReturnValue", "SameParameterValue"})
    private <A extends Annotation, R> R processAndPut(
            Map<String, Object> map, String name, Element el, R defaultVal, Class<A> annotationType, Class<R> returnType) {
        return processAndPut( map, name, el, defaultVal, annotationType, returnType, "value" );
    }

    private <A extends Annotation, R> R processAndPut(
            Map<String, Object> map, String name, Element el, R defaultVal, Class<A> annotationType, Class<R> returnType, String methodName) {
        R result = process( el, defaultVal, annotationType, returnType, methodName );
        if ( result != null )
            map.put( name, result );
        return result;
    }

    @SuppressWarnings("unchecked")
    private <A extends Annotation, R> R process(Element el, R defaultVal, Class<A> annotationType, Class<R> returnType, String methodName) {
        R result;
        A ann = el.getAnnotation( annotationType );
        if ( ann == null ) result = defaultVal;
        else {
            try {
                Method value = annotationType.getMethod( methodName );
                Object res = value.invoke( ann );
                result = ( R ) ( returnType == String.class ? res.toString() : returnType.cast( res ) );
            } catch ( Exception e ) {
                throw new RuntimeException( e ); // shouldn't happen in theory (blame Choco if it does)
            }
        }
        return result;
    }

    private boolean processExternalCommands(Set<? extends Element> commandExecutors, TypeElement mainPluginType, Map<String, Map<String, Object>> commandMetadata) {
        for ( Element element : commandExecutors ) {
            // Check to see if someone annotated a non-class with this
            if ( !( element instanceof TypeElement ) ) {
                this.raiseError( "Specified Command Executor class is not a class." );
                return false;
            }

            TypeElement typeElement = ( TypeElement ) element;
            if ( typeElement.equals( mainPluginType ) ) {
                continue;
            }

            Commands annotation = typeElement.getAnnotation( Commands.class );
            if ( annotation != null && annotation.value().length > 0 ) {
                commandMetadata.putAll( this.processCommands( annotation ) );
            }
        }
        return true;
    }

    /**
     * Processes a set of commands.
     *
     * @param commands The annotation.
     *
     * @return The generated command metadata.
     */
    protected Map<String, Map<String, Object>> processCommands(Commands commands) {
        Map<String, Map<String, Object>> commandList = new LinkedHashMap<>();
        for ( Command command : commands.value() ) {
            commandList.put( command.name(), this.processCommand( command ) );
        }
        return commandList;
    }

    /**
     * Processes a single command.
     *
     * @param commandAnnotation The annotation.
     *
     * @return The generated command metadata.
     */
    protected Map<String, Object> processCommand(Command commandAnnotation) {
        Map<String, Object> command = new LinkedHashMap<>();

        if ( commandAnnotation.aliases().length == 1 ) {
            command.put( "aliases", commandAnnotation.aliases()[ 0 ] );
        } else if ( commandAnnotation.aliases().length > 1 ) {
            command.put( "aliases", commandAnnotation.aliases() );
        }

        if ( !commandAnnotation.desc().isEmpty() ) {
            command.put( "description", commandAnnotation.desc() );
        }
        if ( !commandAnnotation.permission().isEmpty() ) {
            command.put( "permission", commandAnnotation.permission() );
        }
        if ( !commandAnnotation.permissionMessage().isEmpty() ) {
            command.put( "permission-message", commandAnnotation.permissionMessage() );
        }
        if ( !commandAnnotation.usage().isEmpty() ) {
            command.put( "usage", commandAnnotation.usage() );
        }

        return command;
    }

    /**
     * Processes a command.
     *
     * @param permissionAnnotation The annotation.
     *
     * @return The generated permission metadata.
     */
    protected Map<String, Object> processPermission(Permission permissionAnnotation) {
        Map<String, Object> permission = new LinkedHashMap<>();

        if ( !"".equals( permissionAnnotation.desc() ) ) {
            permission.put( "description", permissionAnnotation.desc() );
        }
        if ( PermissionDefault.OP != permissionAnnotation.defaultValue() ) {
            permission.put( "default", permissionAnnotation.defaultValue().toString().toLowerCase() );
        }

        if ( permissionAnnotation.children().length > 0 ) {
            Map<String, Boolean> childrenList = new LinkedHashMap<>(); // maintain order
            for ( ChildPermission childPermission : permissionAnnotation.children() ) {
                childrenList.put( childPermission.name(), childPermission.inherit() );
            }
            permission.put( "children", childrenList );
        }

        return permission;
    }

    /**
     * Processes a set of permissions.
     *
     * @param permissions The annotation.
     *
     * @return The generated permission metadata.
     */
    protected Map<String, Map<String, Object>> processPermissions(Permissions permissions) {
        Map<String, Map<String, Object>> permissionList = new LinkedHashMap<>();
        for ( Permission permission : permissions.value() ) {
            permissionList.put( permission.name(), this.processPermission( permission ) );
        }
        return permissionList;
    }
}
