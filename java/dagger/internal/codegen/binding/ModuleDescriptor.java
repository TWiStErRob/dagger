/*
 * Copyright (C) 2015 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen.binding;

import static androidx.room.compiler.processing.compat.XConverters.toJavac;
import static androidx.room.compiler.processing.compat.XConverters.toXProcessing;
import static com.google.auto.common.MoreElements.asExecutable;
import static com.google.auto.common.MoreElements.getPackage;
import static com.google.auto.common.MoreElements.isAnnotationPresent;
import static com.google.common.base.CaseFormat.LOWER_CAMEL;
import static com.google.common.base.CaseFormat.UPPER_CAMEL;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.Collections2.transform;
import static dagger.internal.codegen.base.ModuleAnnotation.moduleAnnotation;
import static dagger.internal.codegen.base.Util.reentrantComputeIfAbsent;
import static dagger.internal.codegen.binding.SourceFiles.classFileName;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableSet;
import static dagger.internal.codegen.langmodel.DaggerElements.getMethodDescriptor;
import static dagger.internal.codegen.langmodel.DaggerElements.isAnnotationPresent;
import static javax.lang.model.type.TypeKind.DECLARED;
import static javax.lang.model.type.TypeKind.NONE;
import static javax.lang.model.util.ElementFilter.methodsIn;

import androidx.room.compiler.processing.XProcessingEnv;
import androidx.room.compiler.processing.XTypeElement;
import com.google.auto.common.MoreElements;
import com.google.auto.common.MoreTypes;
import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.Traverser;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.squareup.javapoet.ClassName;
import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.internal.codegen.base.ClearableCache;
import dagger.internal.codegen.kotlin.KotlinMetadataUtil;
import dagger.internal.codegen.langmodel.DaggerElements;
import dagger.multibindings.Multibinds;
import dagger.producers.Produces;
import dagger.spi.model.Key;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/** Contains metadata that describes a module. */
@AutoValue
public abstract class ModuleDescriptor {

  public abstract TypeElement moduleElement();

  abstract ImmutableSet<TypeElement> includedModules();

  public abstract ImmutableSet<ContributionBinding> bindings();

  /** The multibinding declarations contained in this module. */
  abstract ImmutableSet<MultibindingDeclaration> multibindingDeclarations();

  /** The {@link Module#subcomponents() subcomponent declarations} contained in this module. */
  abstract ImmutableSet<SubcomponentDeclaration> subcomponentDeclarations();

  /** The {@link Binds} method declarations that define delegate bindings. */
  abstract ImmutableSet<DelegateDeclaration> delegateDeclarations();

  /** The {@link BindsOptionalOf} method declarations that define optional bindings. */
  abstract ImmutableSet<OptionalBindingDeclaration> optionalDeclarations();

  /** The kind of the module. */
  public abstract ModuleKind kind();

  /** Returns all of the bindings declared in this module. */
  @Memoized
  public ImmutableSet<BindingDeclaration> allBindingDeclarations() {
    return ImmutableSet.<BindingDeclaration>builder()
        .addAll(bindings())
        .addAll(delegateDeclarations())
        .addAll(multibindingDeclarations())
        .addAll(optionalDeclarations())
        .addAll(subcomponentDeclarations())
        .build();
  }

  /** Returns the keys of all bindings declared by this module. */
  ImmutableSet<Key> allBindingKeys() {
    return allBindingDeclarations().stream().map(BindingDeclaration::key).collect(toImmutableSet());
  }

  /** A {@link ModuleDescriptor} factory. */
  @Singleton
  public static final class Factory implements ClearableCache {
    private final XProcessingEnv processingEnv;
    private final DaggerElements elements;
    private final KotlinMetadataUtil metadataUtil;
    private final BindingFactory bindingFactory;
    private final MultibindingDeclaration.Factory multibindingDeclarationFactory;
    private final DelegateDeclaration.Factory bindingDelegateDeclarationFactory;
    private final SubcomponentDeclaration.Factory subcomponentDeclarationFactory;
    private final OptionalBindingDeclaration.Factory optionalBindingDeclarationFactory;
    private final Map<TypeElement, ModuleDescriptor> cache = new HashMap<>();

    @Inject
    Factory(
        XProcessingEnv processingEnv,
        DaggerElements elements,
        KotlinMetadataUtil metadataUtil,
        BindingFactory bindingFactory,
        MultibindingDeclaration.Factory multibindingDeclarationFactory,
        DelegateDeclaration.Factory bindingDelegateDeclarationFactory,
        SubcomponentDeclaration.Factory subcomponentDeclarationFactory,
        OptionalBindingDeclaration.Factory optionalBindingDeclarationFactory) {
      this.processingEnv = processingEnv;
      this.elements = elements;
      this.metadataUtil = metadataUtil;
      this.bindingFactory = bindingFactory;
      this.multibindingDeclarationFactory = multibindingDeclarationFactory;
      this.bindingDelegateDeclarationFactory = bindingDelegateDeclarationFactory;
      this.subcomponentDeclarationFactory = subcomponentDeclarationFactory;
      this.optionalBindingDeclarationFactory = optionalBindingDeclarationFactory;
    }

    public ModuleDescriptor create(XTypeElement moduleElement) {
      return create(toJavac(moduleElement));
    }

    public ModuleDescriptor create(TypeElement moduleElement) {
      return reentrantComputeIfAbsent(cache, moduleElement, this::createUncached);
    }

    public ModuleDescriptor createUncached(TypeElement moduleElement) {
      ImmutableSet.Builder<ContributionBinding> bindings = ImmutableSet.builder();
      ImmutableSet.Builder<DelegateDeclaration> delegates = ImmutableSet.builder();
      ImmutableSet.Builder<MultibindingDeclaration> multibindingDeclarations =
          ImmutableSet.builder();
      ImmutableSet.Builder<OptionalBindingDeclaration> optionalDeclarations =
          ImmutableSet.builder();

      for (ExecutableElement moduleMethod : methodsIn(elements.getAllMembers(moduleElement))) {
        if (isAnnotationPresent(moduleMethod, Provides.class)) {
          bindings.add(bindingFactory.providesMethodBinding(moduleMethod, moduleElement));
        }
        if (isAnnotationPresent(moduleMethod, Produces.class)) {
          bindings.add(bindingFactory.producesMethodBinding(moduleMethod, moduleElement));
        }
        if (isAnnotationPresent(moduleMethod, Binds.class)) {
          delegates.add(bindingDelegateDeclarationFactory.create(moduleMethod, moduleElement));
        }
        if (isAnnotationPresent(moduleMethod, Multibinds.class)) {
          multibindingDeclarations.add(
              multibindingDeclarationFactory.forMultibindsMethod(moduleMethod, moduleElement));
        }
        if (isAnnotationPresent(moduleMethod, BindsOptionalOf.class)) {
          optionalDeclarations.add(
              optionalBindingDeclarationFactory.forMethod(moduleMethod, moduleElement));
        }
      }

      if (metadataUtil.hasEnclosedCompanionObject(moduleElement)) {
        collectCompanionModuleBindings(moduleElement, bindings);
      }

      return new AutoValue_ModuleDescriptor(
          moduleElement,
          ImmutableSet.copyOf(collectIncludedModules(new LinkedHashSet<>(), moduleElement)),
          bindings.build(),
          multibindingDeclarations.build(),
          subcomponentDeclarationFactory.forModule(toXProcessing(moduleElement, processingEnv)),
          delegates.build(),
          optionalDeclarations.build(),
          ModuleKind.forAnnotatedElement(moduleElement).get());
    }

    private void collectCompanionModuleBindings(
        TypeElement moduleElement, ImmutableSet.Builder<ContributionBinding> bindings) {
      checkArgument(metadataUtil.hasEnclosedCompanionObject(moduleElement));
      TypeElement companionModule = metadataUtil.getEnclosedCompanionObject(moduleElement);
      ImmutableSet<String> bindingElementDescriptors =
          bindings.build().stream()
              .map(binding -> getMethodDescriptor(asExecutable(binding.bindingElement().get())))
              .collect(toImmutableSet());
      methodsIn(elements.getAllMembers(companionModule)).stream()
          // Binding methods in companion objects with @JvmStatic are mirrored in the enclosing
          // class, therefore we should ignore it or else it'll be a duplicate binding.
          .filter(method -> !KotlinMetadataUtil.isJvmStaticPresent(method))
          // Fallback strategy for de-duping contributing bindings in the companion module with
          // @JvmStatic by comparing descriptors. Contributing bindings are the only valid bindings
          // a companion module can declare. See: https://youtrack.jetbrains.com/issue/KT-35104
          // TODO(danysantiago): Checks qualifiers too.
          .filter(method -> !bindingElementDescriptors.contains(getMethodDescriptor(method)))
          .forEach(
              method -> {
                if (isAnnotationPresent(method, Provides.class)) {
                  bindings.add(bindingFactory.providesMethodBinding(method, companionModule));
                }
                if (isAnnotationPresent(method, Produces.class)) {
                  bindings.add(bindingFactory.producesMethodBinding(method, companionModule));
                }
              });
    }

    /** Returns all the modules transitively included by given modules, including the arguments. */
    ImmutableSet<ModuleDescriptor> transitiveModules(Collection<XTypeElement> modules) {
      // Traverse as a graph to automatically handle modules with cyclic includes.
      return ImmutableSet.copyOf(
          Traverser.forGraph(
                  (ModuleDescriptor module) -> transform(module.includedModules(), this::create))
              .depthFirstPreOrder(transform(modules, this::create)));
    }

    @CanIgnoreReturnValue
    private Set<TypeElement> collectIncludedModules(
        Set<TypeElement> includedModules, TypeElement moduleElement) {
      TypeMirror superclass = moduleElement.getSuperclass();
      if (!superclass.getKind().equals(NONE)) {
        verify(superclass.getKind().equals(DECLARED));
        TypeElement superclassElement = MoreTypes.asTypeElement(superclass);
        if (!superclassElement.getQualifiedName().contentEquals(Object.class.getCanonicalName())) {
          collectIncludedModules(includedModules, superclassElement);
        }
      }
      moduleAnnotation(moduleElement)
          .ifPresent(
              moduleAnnotation -> {
                includedModules.addAll(moduleAnnotation.includes());
                includedModules.addAll(implicitlyIncludedModules(moduleElement));
              });
      return includedModules;
    }

    // @ContributesAndroidInjector generates a module that is implicitly included in the enclosing
    // module
    private ImmutableSet<TypeElement> implicitlyIncludedModules(TypeElement moduleElement) {
      TypeElement contributesAndroidInjector =
          elements.getTypeElement("dagger.android.ContributesAndroidInjector");
      if (contributesAndroidInjector == null) {
        return ImmutableSet.of();
      }
      return methodsIn(moduleElement.getEnclosedElements()).stream()
          .filter(method -> isAnnotationPresent(method, contributesAndroidInjector.asType()))
          .map(method -> elements.checkTypePresent(implicitlyIncludedModuleName(method)))
          .collect(toImmutableSet());
    }

    private String implicitlyIncludedModuleName(ExecutableElement method) {
      return getPackage(method).getQualifiedName()
          + "."
          + classFileName(ClassName.get(MoreElements.asType(method.getEnclosingElement())))
          + "_"
          + LOWER_CAMEL.to(UPPER_CAMEL, method.getSimpleName().toString());
    }

    @Override
    public void clearCache() {
      cache.clear();
    }
  }
}
