/*
 * Copyright (C) 2023 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.redwood.tooling.schema

import app.cash.redwood.tooling.schema.Deprecation.Level
import app.cash.redwood.tooling.schema.ProtocolWidget.ProtocolChildren
import app.cash.redwood.tooling.schema.ProtocolWidget.ProtocolProperty
import app.cash.redwood.tooling.schema.SchemaAnnotation.DependencyAnnotation
import java.io.File
import java.net.URLClassLoader
import org.jetbrains.kotlin.KtVirtualFileSourceFile
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoots
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.VfsBasedProjectEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.pipeline.ModuleCompilerEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.pipeline.ModuleCompilerInput
import org.jetbrains.kotlin.cli.jvm.compiler.pipeline.compileModuleToAnalyzedFir
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.config.CommonConfigurationKeys.MODULE_NAME
import org.jetbrains.kotlin.config.CommonConfigurationKeys.USE_FIR
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.descriptors.ClassKind.OBJECT
import org.jetbrains.kotlin.diagnostics.DiagnosticReporterFactory
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.declarations.utils.isData
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirArrayOfCall
import org.jetbrains.kotlin.fir.expressions.FirConstExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.builder.toAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.resolve.fqName
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil.DEFAULT_MODULE_NAME
import org.jetbrains.kotlin.modules.TargetId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.CommonPlatforms
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.text

public fun parseSchema(
  sources: Collection<File>,
  dependencies: Collection<File>,
  type: FqType,
): SchemaSet {
  return parseProtocolSchema(sources, dependencies, type)
}

public fun parseProtocolSchema(
  sources: Collection<File>,
  dependencies: Collection<File>,
  type: FqType,
): ProtocolSchemaSet {
  val messageCollector = object : MessageCollector {
    override fun clear() = Unit
    override fun hasErrors() = false

    override fun report(
      severity: CompilerMessageSeverity,
      message: String,
      location: CompilerMessageSourceLocation?,
    ) {
      println("$severity: $message")
    }
  }

  val configuration = CompilerConfiguration()
  configuration.put(MODULE_NAME, "schema")
  configuration.put(MESSAGE_COLLECTOR_KEY, messageCollector)
  configuration.put(USE_FIR, true)
  configuration.addKotlinSourceRoots(sources.map { it.absolutePath })
  // TODO Figure out how to add the JDK modules to the classpath. Currently importing the stdlib
  //  allows a typealias to resolve to a JDK type which doesn't exist and thus breaks analysis.
  configuration.addJvmClasspathRoots(dependencies.filter { "kotlin-stdlib-" !in it.path })

  val disposable = Disposer.newDisposable()
  val environment = KotlinCoreEnvironment.createForProduction(
    disposable,
    configuration,
    EnvironmentConfigFiles.JVM_CONFIG_FILES,
  )
  val project = environment.project

  val localFileSystem = VirtualFileManager.getInstance().getFileSystem(
    StandardFileSystems.FILE_PROTOCOL,
  )
  val files = buildList {
    for (source in sources) {
      source.walkTopDown().filter { it.isFile }.forEach {
        this += localFileSystem.findFileByPath(it.absolutePath)!!
      }
    }
  }

  val input = ModuleCompilerInput(
    targetId = TargetId(DEFAULT_MODULE_NAME, "redwood-parser"),
    commonPlatform = CommonPlatforms.defaultCommonPlatform,
    commonSources = emptyList(),
    platform = JvmPlatforms.unspecifiedJvmPlatform,
    platformSources = files.map(::KtVirtualFileSourceFile),
    configuration = configuration,
  )

  val reporter = DiagnosticReporterFactory.createReporter()

  val globalScope = GlobalSearchScope.allScope(project)
  val packagePartProvider = environment.createPackagePartProvider(globalScope)
  val projectEnvironment = VfsBasedProjectEnvironment(
    project = project,
    localFileSystem = localFileSystem,
    getPackagePartProviderFn = { packagePartProvider },
  )

  val output = compileModuleToAnalyzedFir(
    input = input,
    environment = ModuleCompilerEnvironment(
      projectEnvironment = projectEnvironment,
      diagnosticsReporter = reporter,
    ),
    previousStepsSymbolProviders = emptyList(),
    incrementalExcludesScope = null,
    diagnosticsReporter = reporter,
    performanceManager = null,
  )
  val platformOutput = output.platformOutput
  val firFiles = platformOutput.fir
  val firSession = platformOutput.session

  val types = firFiles
    .flatMap { it.declarations.findRegularClassesRecursive() }
    .associateBy { it.classId.asSingleFqName().toFqType() }

  val firContext = FirContext(types, firSession)

  val schema = firContext.parseSchema(type)

  disposable.dispose()

  val dependencyClassLoader = URLClassLoader(dependencies.map { it.toURI().toURL() }.toTypedArray())
  val dependencySchemas = schema.taggedDependencies.entries
    .associate { (dependencyTag, dependencyType) ->
      require(dependencyTag != 0) {
        "Dependency $dependencyType tag must not be non-zero"
      }

      val dependency = loadProtocolSchema(
        type = dependencyType,
        classLoader = dependencyClassLoader,
        tag = dependencyTag,
      )
      dependencyTag to dependency
    }

  val schemaSet = ParsedProtocolSchemaSet(
    schema,
    dependencySchemas.values.associateBy { it.type },
  )

  val duplicatedWidgets = schemaSet.all
    .flatMap { it.widgets.map { widget -> widget to it } }
    .groupBy { it.first.type }
    .filterValues { it.size > 1 }
    .mapValues { it.value.map(Pair<*, Schema>::second) }
  if (duplicatedWidgets.isNotEmpty()) {
    throw IllegalArgumentException(
      buildString {
        appendLine("Schema dependency tree contains duplicated widgets")
        for ((widget, schemas) in duplicatedWidgets) {
          append("\n- $widget: ")
          schemas.joinTo(this) { it.type.toString() }
        }
      },
    )
  }

  return schemaSet
}

private fun List<FirDeclaration>.findRegularClassesRecursive(): List<FirRegularClass> {
  val classes = filterIsInstance<FirRegularClass>()
  return classes + classes.flatMap { it.declarations.findRegularClassesRecursive() }
}

private class FirContext(
  val firClassByName: Map<FqType, FirRegularClass>,
  val firSession: FirSession,
)

private fun FirContext.parseSchema(type: FqType): ParsedProtocolSchema {
  val firClass = firClassByName[type]
    ?: throw IllegalArgumentException("Unable to locate schema type $type")

  val schemaAnnotation = findSchemaAnnotation(firClass.annotations)
    ?: throw IllegalArgumentException("Schema $type missing @Schema annotation")

  val duplicatedMembers = schemaAnnotation.members
    .groupBy { it }
    .filterValues { it.size > 1 }
    .keys
  if (duplicatedMembers.isNotEmpty()) {
    throw IllegalArgumentException(
      buildString {
        append("Schema contains repeated member")
        if (duplicatedMembers.size > 1) {
          append('s')
        }
        duplicatedMembers.joinTo(this, prefix = "\n\n- ", separator = "\n- ")
      },
    )
  }

  val widgets = mutableListOf<ParsedProtocolWidget>()
  val layoutModifiers = mutableListOf<ParsedProtocolLayoutModifier>()
  for (memberType in schemaAnnotation.members) {
    val memberClass = firClassByName[memberType]
      ?: throw IllegalArgumentException("Unable to locate schema type $memberType")

    val widgetAnnotation = findWidgetAnnotation(memberClass.annotations)
    val layoutModifierAnnotation = findLayoutModifierAnnotation(memberClass.annotations)

    if ((widgetAnnotation == null) == (layoutModifierAnnotation == null)) {
      throw IllegalArgumentException(
        "$memberType must be annotated with either @Widget or @LayoutModifier",
      )
    } else if (widgetAnnotation != null) {
      widgets += parseWidget(memberType, memberClass, widgetAnnotation)
    } else if (layoutModifierAnnotation != null) {
      layoutModifiers += parseLayoutModifier(memberType, memberClass, layoutModifierAnnotation)
    } else {
      throw AssertionError()
    }
  }

  val badWidgets = widgets.groupBy(ProtocolWidget::tag).filterValues { it.size > 1 }
  if (badWidgets.isNotEmpty()) {
    throw IllegalArgumentException(
      buildString {
        appendLine("Schema @Widget tags must be unique")
        for ((widgetTag, group) in badWidgets) {
          append("\n- @Widget($widgetTag): ")
          group.joinTo(this) { it.type.toString() }
        }
      },
    )
  }

  val badLayoutModifiers = layoutModifiers.groupBy(ProtocolLayoutModifier::tag).filterValues { it.size > 1 }
  if (badLayoutModifiers.isNotEmpty()) {
    throw IllegalArgumentException(
      buildString {
        appendLine("Schema @LayoutModifier tags must be unique")
        for ((modifierTag, group) in badLayoutModifiers) {
          append("\n- @LayoutModifier($modifierTag): ")
          group.joinTo(this) { it.type.toString() }
        }
      },
    )
  }

  val widgetScopes = widgets
    .flatMap { it.traits }
    .filterIsInstance<Widget.Children>()
    .mapNotNull { it.scope }
  val layoutModifierScopes = layoutModifiers
    .flatMap { it.scopes }
  val scopes = buildSet {
    addAll(widgetScopes)
    addAll(layoutModifierScopes)
  }

  val badDependencyTags = schemaAnnotation.dependencies
    .groupBy { it.tag }
    .filterValues { it.size > 1 }
  if (badDependencyTags.isNotEmpty()) {
    throw IllegalArgumentException(
      buildString {
        appendLine("Schema dependency tags must be unique")
        for ((dependencyTag, group) in badDependencyTags) {
          append("\n- Dependency tag $dependencyTag: ")
          group.joinTo(this) { it.schema.toString() }
        }
      },
    )
  }

  val badDependencyTypes = schemaAnnotation.dependencies
    .groupBy { it.schema }
    .filterValues { it.size > 1 }
    .keys
  if (badDependencyTypes.isNotEmpty()) {
    throw IllegalArgumentException(
      buildString {
        append("Schema contains repeated ")
        append(if (badDependencyTypes.size > 1) "dependencies" else "dependency")
        badDependencyTypes.joinTo(this, prefix = "\n\n- ", separator = "\n- ")
      },
    )
  }

  return ParsedProtocolSchema(
    type = type,
    scopes = scopes.toList(),
    widgets = widgets,
    layoutModifiers = layoutModifiers,
    taggedDependencies = schemaAnnotation.dependencies.associate { it.tag to it.schema },
  )
}

private fun FirContext.parseWidget(
  memberType: FqType,
  firClass: FirRegularClass,
  annotation: WidgetAnnotation,
): ParsedProtocolWidget {
  val tag = annotation.tag
  require(tag in 1 until maxMemberTag) {
    "@Widget $memberType tag must be in range [1, $maxMemberTag): $tag"
  }

  val traits = if (firClass.isData) {
    firClass.primaryConstructorIfAny(firSession)!!.valueParameterSymbols.map { parameter ->
      val name = parameter.name.identifier

      val propertyAnnotation = findPropertyAnnotation(parameter.annotations)
      val childrenAnnotation = findChildrenAnnotation(parameter.annotations)
      val defaultAnnotation = findDefaultAnnotation(parameter.annotations)
      val deprecation = findDeprecationAnnotation(parameter.annotations)
        ?.toDeprecation()

      if (propertyAnnotation != null) {
        val parameterType = parameter.resolvedReturnType.classId!!.asSingleFqName().toFqType()
        ParsedProtocolProperty(
          tag = propertyAnnotation.tag,
          name = name,
          type = parameterType,
          defaultExpression = defaultAnnotation?.expression,
          deprecation = deprecation,
        )
      } else if (childrenAnnotation != null) {
        val scope: FqType? = null
        ParsedProtocolChildren(
          tag = childrenAnnotation.tag,
          name = name,
          scope = scope,
          defaultExpression = defaultAnnotation?.expression,
          deprecation = deprecation,
        )
      } else {
        throw IllegalArgumentException("Unannotated parameter \"$name\" on $memberType")
      }
    }
  } else if (firClass.classKind == OBJECT) {
    emptyList()
  } else {
    throw IllegalArgumentException(
      "@Widget $memberType must be 'data' class or 'object'",
    )
  }

  val badChildren = traits.filterIsInstance<ProtocolChildren>()
    .groupBy(ProtocolChildren::tag)
    .filterValues { it.size > 1 }
  if (badChildren.isNotEmpty()) {
    throw IllegalArgumentException(
      buildString {
        appendLine("$memberType's @Children tags must be unique")
        for ((childTag, group) in badChildren) {
          append("\n- @Children($childTag): ")
          group.joinTo(this) { it.name }
        }
      },
    )
  }

  val badProperties = traits.filterIsInstance<ProtocolProperty>()
    .groupBy(ProtocolProperty::tag)
    .filterValues { it.size > 1 }
  if (badProperties.isNotEmpty()) {
    throw IllegalArgumentException(
      buildString {
        appendLine("$memberType's @Property tags must be unique")
        for ((propertyTag, group) in badProperties) {
          append("\n- @Property($propertyTag): ")
          group.joinTo(this) { it.name }
        }
      },
    )
  }

  val deprecation = findDeprecationAnnotation(firClass.annotations)
    ?.toDeprecation()

  return ParsedProtocolWidget(
    tag = tag,
    type = memberType,
    deprecation = deprecation,
    traits = traits,
  )
}

private fun FirContext.parseLayoutModifier(
  memberType: FqType,
  firClass: FirRegularClass,
  annotation: LayoutModifierAnnotation,
): ParsedProtocolLayoutModifier {
  val tag = annotation.tag
  require(tag in 1 until maxMemberTag) {
    "@LayoutModifier $memberType tag must be in range [1, $maxMemberTag): $tag"
  }
  require(annotation.scopes.isNotEmpty()) {
    "@LayoutModifier $memberType must have at least one scope."
  }

  val properties = if (firClass.isData) {
    TODO()
  } else if (firClass.classKind == OBJECT) {
    emptyList<ParsedProtocolLayoutModifierProperty>()
  } else {
    throw IllegalArgumentException(
      "@LayoutModifier $memberType must be 'data' class or 'object'",
    )
  }

  val deprecation = findDeprecationAnnotation(firClass.annotations)
    ?.toDeprecation()

  return ParsedProtocolLayoutModifier(
    tag = tag,
    scopes = annotation.scopes,
    type = memberType,
    deprecation = deprecation,
    properties = properties,
  )
}

private fun FirContext.findSchemaAnnotation(
  annotations: List<FirAnnotation>,
): SchemaAnnotation? {
  val annotation = annotations.find { it.fqName(firSession) == Annotations.Schema }
    ?: return null

  val membersArray = annotation.argumentMapping
    .mapping[Name.identifier("members")] as? FirArrayOfCall
    ?: throw AssertionError(annotation.source?.text)
  val members = membersArray.argumentList
    .arguments
    .map {
      val getClassCall = it as? FirGetClassCall
        ?: throw AssertionError(annotation.source?.text)
      val resolvedQualifier = getClassCall.argument as? FirResolvedQualifier
        ?: throw AssertionError(annotation.source?.text)
      val classId = resolvedQualifier.classId
        ?: throw AssertionError(annotation.source?.text)
      classId.asSingleFqName().toFqType()
    }

  val dependenciesArray = annotation.argumentMapping
    .mapping[Name.identifier("dependencies")] as? FirArrayOfCall
  val dependencies = dependenciesArray?.arguments.orEmpty()
    .map {
      val functionCall = it as? FirFunctionCall
        ?: throw AssertionError(annotation.source?.text)
      val mapping = functionCall.argumentList.toAnnotationArgumentMapping().mapping

      @Suppress("UNCHECKED_CAST")
      val tagExpression = mapping[Name.identifier("tag")] as? FirConstExpression<Int>
        ?: throw AssertionError(annotation.source?.text)
      val tag = tagExpression.value

      val getClassCall = mapping[Name.identifier("schema")] as? FirGetClassCall
        ?: throw AssertionError(annotation.source?.text)
      val resolvedQualifier = getClassCall.argument as? FirResolvedQualifier
        ?: throw AssertionError(annotation.source?.text)
      val classId = resolvedQualifier.classId
        ?: throw AssertionError(annotation.source?.text)
      val fqType = classId.asSingleFqName().toFqType()

      DependencyAnnotation(tag, fqType)
    }

  return SchemaAnnotation(members, dependencies)
}

private data class SchemaAnnotation(
  val members: List<FqType>,
  val dependencies: List<DependencyAnnotation>,
) {
  data class DependencyAnnotation(
    val tag: Int,
    val schema: FqType,
  )
}

private fun FirContext.findWidgetAnnotation(
  annotations: List<FirAnnotation>,
): WidgetAnnotation? {
  val annotation = annotations.find { it.fqName(firSession) == Annotations.Widget }
    ?: return null

  @Suppress("UNCHECKED_CAST")
  val tagExpression = annotation.argumentMapping
    .mapping[Name.identifier("tag")] as? FirConstExpression<Int>
    ?: throw AssertionError(annotation.source?.text)

  return WidgetAnnotation(tagExpression.value)
}

private data class WidgetAnnotation(
  val tag: Int,
)

private fun FirContext.findPropertyAnnotation(
  annotations: List<FirAnnotation>,
): PropertyAnnotation? {
  val annotation = annotations.find { it.fqName(firSession) == Annotations.Property }
    ?: return null

  @Suppress("UNCHECKED_CAST")
  val tagExpression = annotation.argumentMapping
    .mapping[Name.identifier("tag")] as? FirConstExpression<Int>
    ?: throw AssertionError(annotation.source?.text)

  return PropertyAnnotation(tagExpression.value)
}

private data class PropertyAnnotation(
  val tag: Int,
)

private fun FirContext.findChildrenAnnotation(
  annotations: List<FirAnnotation>,
): ChildrenAnnotation? {
  val annotation = annotations.find { it.fqName(firSession) == Annotations.Children }
    ?: return null

  @Suppress("UNCHECKED_CAST")
  val tagExpression = annotation.argumentMapping
    .mapping[Name.identifier("tag")] as? FirConstExpression<Int>
    ?: throw AssertionError(annotation.source?.text)

  return ChildrenAnnotation(tagExpression.value)
}

private data class ChildrenAnnotation(
  val tag: Int,
)

@Suppress("UNCHECKED_CAST")
private fun FirContext.findDefaultAnnotation(
  annotations: List<FirAnnotation>,
): DefaultAnnotation? {
  val annotation = annotations.find { it.fqName(firSession) == Annotations.Default }
    ?: return null

  val expression = annotation.argumentMapping
    .mapping[Name.identifier("expression")] as? FirConstExpression<String>
    ?: throw AssertionError(annotation.source?.text)

  return DefaultAnnotation(expression.value)
}

private data class DefaultAnnotation(
  val expression: String,
)

@Suppress("UNCHECKED_CAST")
private fun FirContext.findLayoutModifierAnnotation(
  annotations: List<FirAnnotation>,
): LayoutModifierAnnotation? {
  val annotation = annotations.find { it.fqName(firSession) == Annotations.LayoutModifier }
    ?: return null

  @Suppress("UNCHECKED_CAST")
  val tagExpression = annotation.argumentMapping.mapping[Name.identifier("tag")] as? FirConstExpression<Int>
    ?: throw AssertionError(annotation.source?.text)

  val scopesExpression = annotation.argumentMapping.mapping[Name.identifier("scopes")] as? FirVarargArgumentsExpression
  val scopes = scopesExpression?.arguments.orEmpty()
    .map {
      val getClassCall = it as? FirGetClassCall
        ?: throw AssertionError(annotation.source?.text)
      val resolvedQualifier = getClassCall.argument as? FirResolvedQualifier
        ?: throw AssertionError(annotation.source?.text)
      val classId = resolvedQualifier.classId
        ?: throw AssertionError(annotation.source?.text)
      classId.asSingleFqName().toFqType()
    }

  return LayoutModifierAnnotation(tagExpression.value, scopes)
}

private data class LayoutModifierAnnotation(
  val tag: Int,
  val scopes: List<FqType>,
)

private fun FirContext.findDeprecationAnnotation(
  annotations: List<FirAnnotation>,
): DeprecationAnnotation? {
  val annotation = annotations.find { it.fqName(firSession) == Annotations.Deprecated }
    ?: return null

  @Suppress("UNCHECKED_CAST")
  val messageExpression = annotation.argumentMapping
    .mapping[Name.identifier("message")] as? FirConstExpression<String>
    ?: throw AssertionError(annotation.source?.text)

  @Suppress("UNCHECKED_CAST")
  val levelExpression = annotation.argumentMapping
    .mapping[Name.identifier("level")] as? FirConstExpression<String>
    ?: throw AssertionError(annotation.source?.text)

  return DeprecationAnnotation(messageExpression.value, levelExpression.value)
}

private data class DeprecationAnnotation(
  val message: String,
  val level: String,
)

private fun DeprecationAnnotation.toDeprecation(): ParsedDeprecation {
  return ParsedDeprecation(
    level = when (level) {
      "WARNING" -> Level.WARNING
      "ERROR" -> Level.ERROR
      else -> {
        throw IllegalArgumentException(
          "Schema deprecation does not support level $level: $this",
        )
      }
    },
    message = message,
  )
}

private fun FqName.toFqType() = FqType.bestGuess(asString())

private object Annotations {
  val Children = FqName("app.cash.redwood.schema.Children")
  val Default = FqName("app.cash.redwood.schema.Default")
  val Deprecated = FqName("kotlin.Deprecated")
  val LayoutModifier = FqName("app.cash.redwood.schema.LayoutModifier")
  val Property = FqName("app.cash.redwood.schema.Property")
  val Schema = FqName("app.cash.redwood.schema.Schema")
  val Widget = FqName("app.cash.redwood.schema.Widget")
}