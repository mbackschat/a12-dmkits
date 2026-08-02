import {fileURLToPath} from "node:url";
import {dirname, relative, resolve} from "node:path";

import ts from "typescript";

export type BoundaryClassification = Readonly<{
  readonly disposition: "hostile-packed-test" | "safe";
  readonly reason: string;
}>;

const publicDeclaration = (symbol: ts.Symbol): boolean => {
  const declarations = symbol.getDeclarations();
  return declarations === undefined || declarations.every((declaration) => {
    const modifiers = ts.canHaveModifiers(declaration)
      ? ts.getModifiers(declaration)
      : undefined;
    return modifiers?.every((modifier) =>
      modifier.kind !== ts.SyntaxKind.PrivateKeyword &&
      modifier.kind !== ts.SyntaxKind.ProtectedKeyword) ?? true;
  });
};

const parameterType = (
  checker: ts.TypeChecker,
  signature: ts.Signature,
  parameter: ts.Symbol,
): ts.Type => checker.getTypeOfSymbolAtLocation(
  parameter,
  parameter.valueDeclaration ?? signature.getDeclaration(),
);

type BoundaryDirection = "caller-to-library" | "library-to-caller";
type ActiveNumberTraversal = Readonly<{
  readonly callerToLibrary: Set<ts.Type>;
  readonly libraryToCaller: Set<ts.Type>;
}>;

const reverseDirection = (direction: BoundaryDirection): BoundaryDirection =>
  direction === "caller-to-library" ? "library-to-caller" : "caller-to-library";

const inboundNumberLeaves = (
  checker: ts.TypeChecker,
  type: ts.Type,
  path: string,
  direction: BoundaryDirection,
  active: ActiveNumberTraversal,
  surfaceRoot: string,
): string[] => {
  if ((type.flags & ts.TypeFlags.NumberLike) !== 0) {
    return direction === "caller-to-library" ? [path] : [];
  }
  if ((type.flags & ts.TypeFlags.TypeParameter) !== 0) {
    const constraint = checker.getBaseConstraintOfType(type);
    return constraint === undefined
      ? []
      : inboundNumberLeaves(checker, constraint, path, direction, active, surfaceRoot);
  }
  if ((type.flags & (ts.TypeFlags.Object | ts.TypeFlags.Union | ts.TypeFlags.Intersection)) === 0) {
    return [];
  }
  const activeTypes = direction === "caller-to-library"
    ? active.callerToLibrary
    : active.libraryToCaller;
  if (activeTypes.has(type)) {
    return [];
  }
  activeTypes.add(type);
  try {
    if (type.isUnionOrIntersection()) {
      return type.types.flatMap((member) =>
        inboundNumberLeaves(checker, member, path, direction, active, surfaceRoot));
    }
    const typeName = type.aliasSymbol?.getName() ?? type.getSymbol()?.getName();
    const typeReference = (type.flags & ts.TypeFlags.Object) !== 0 &&
        (((type as ts.ObjectType).objectFlags & ts.ObjectFlags.Reference) !== 0)
      ? type as ts.TypeReference
      : undefined;
    if ((typeName === "Promise" || typeName === "PromiseLike") && typeReference !== undefined) {
      const promised = checker.getTypeArguments(typeReference)[0];
      return promised === undefined
        ? []
        : inboundNumberLeaves(checker, promised, path, direction, active, surfaceRoot);
    }
    if (checker.isArrayType(type) || checker.isTupleType(type)) {
      const reference = type as ts.TypeReference;
      return checker.getTypeArguments(reference).flatMap((element) =>
        inboundNumberLeaves(checker, element, `${path}[]`, direction, active, surfaceRoot));
    }

    const callbacks = type.getCallSignatures()
      .filter((signature) => declaredWithin(signature.getDeclaration(), surfaceRoot));
    if (callbacks.length > 0) {
      // A callable reverses direction for its parameters and preserves it for its answer. Thus the direct
      // parameter of a caller-supplied callback is output, while a method on that output object reverses
      // direction again and its arguments are caller input.
      return callbacks.flatMap((signature) => [
        ...signature.getParameters().flatMap((parameter) =>
          inboundNumberLeaves(
            checker,
            parameterType(checker, signature, parameter),
            `${path}.${parameter.getName()}`,
            reverseDirection(direction),
            active,
            surfaceRoot,
          )),
        ...inboundNumberLeaves(
          checker,
          signature.getReturnType(),
          `${path}.return`,
          direction,
          active,
          surfaceRoot,
        ),
      ]);
    }

    const properties = checker.getPropertiesOfType(type)
      .filter(publicDeclaration)
      .filter((property) => property.getDeclarations()?.some((declaration) =>
        declaredWithin(declaration, surfaceRoot)) ?? false)
      .flatMap((property) => {
        const declaration = property.valueDeclaration ?? property.getDeclarations()?.[0];
        if (declaration === undefined) {
          return [];
        }
        return inboundNumberLeaves(
          checker,
          checker.getTypeOfSymbolAtLocation(property, declaration),
          `${path}.${property.getName()}`,
          direction,
          active,
          surfaceRoot,
        );
      });
    const stringIndex = checker.getIndexTypeOfType(type, ts.IndexKind.String);
    const indexed = stringIndex === undefined
      ? []
      : inboundNumberLeaves(checker, stringIndex, `${path}.*`, direction, active, surfaceRoot);
    return [...properties, ...indexed];
  } finally {
    activeTypes.delete(type);
  }
};

const signatureLeaves = (
  checker: ts.TypeChecker,
  signature: ts.Signature,
  owner: string,
  surfaceRoot: string,
): string[] => signature.getParameters().flatMap((parameter) =>
  inboundNumberLeaves(
    checker,
    parameterType(checker, signature, parameter),
    `${owner}.${parameter.getName()}`,
    "caller-to-library",
    {callerToLibrary: new Set(), libraryToCaller: new Set()},
    surfaceRoot,
  ));

const declaredWithin = (
  declaration: ts.Declaration | undefined,
  surfaceRoot: string,
): boolean => {
  if (declaration === undefined) {
    return false;
  }
  const path = relative(surfaceRoot, resolve(declaration.getSourceFile().fileName));
  return path !== "" && path !== ".." &&
    !path.startsWith(`..${process.platform === "win32" ? "\\" : "/"}`);
};

const surfaceValueLeaves = (
  checker: ts.TypeChecker,
  type: ts.Type,
  owner: string,
  surfaceRoot: string,
  active: Set<ts.Type>,
  canonicalRuntimeSymbols: ReadonlySet<ts.Symbol>,
  canonicalRoot = false,
): string[] => {
  if (active.has(type)) {
    return [];
  }
  active.add(type);
  try {
    if (type.isUnionOrIntersection()) {
      return type.types.flatMap((member) =>
        surfaceValueLeaves(checker, member, owner, surfaceRoot, active, canonicalRuntimeSymbols));
    }
    if ((type.flags & ts.TypeFlags.TypeParameter) !== 0) {
      const constraint = checker.getBaseConstraintOfType(type);
      return constraint === undefined
        ? []
        : surfaceValueLeaves(checker, constraint, owner, surfaceRoot, active, canonicalRuntimeSymbols);
    }
    if ((type.flags & ts.TypeFlags.Object) === 0) {
      return [];
    }
    const symbol = type.aliasSymbol ?? type.getSymbol();
    if (!canonicalRoot && symbol !== undefined && canonicalRuntimeSymbols.has(symbol)) {
      return [];
    }

    const typeName = type.aliasSymbol?.getName() ?? type.getSymbol()?.getName();
    const typeReference = (((type as ts.ObjectType).objectFlags & ts.ObjectFlags.Reference) !== 0)
      ? type as ts.TypeReference
      : undefined;
    if ((typeName === "Promise" || typeName === "PromiseLike") && typeReference !== undefined) {
      const promised = checker.getTypeArguments(typeReference)[0];
      return promised === undefined
        ? []
        : surfaceValueLeaves(checker, promised, owner, surfaceRoot, active, canonicalRuntimeSymbols);
    }
    if (checker.isArrayType(type) || checker.isTupleType(type)) {
      return checker.getTypeArguments(type as ts.TypeReference).flatMap((element) =>
        surfaceValueLeaves(
          checker,
          element,
          `${owner}[]`,
          surfaceRoot,
          active,
          canonicalRuntimeSymbols,
        ));
    }

    const callable = type.getCallSignatures()
      .filter((signature) => declaredWithin(signature.getDeclaration(), surfaceRoot))
      .flatMap((signature) => [
        ...signatureLeaves(checker, signature, owner, surfaceRoot),
        ...surfaceValueLeaves(
          checker,
          signature.getReturnType(),
          `${owner}.return`,
          surfaceRoot,
          active,
          canonicalRuntimeSymbols,
        ),
      ]);
    const constructable = type.getConstructSignatures()
      .filter((signature) => declaredWithin(signature.getDeclaration(), surfaceRoot))
      .flatMap((signature) => [
        ...signatureLeaves(checker, signature, `${owner}.constructor`, surfaceRoot),
        ...surfaceValueLeaves(
          checker,
          signature.getReturnType(),
          owner,
          surfaceRoot,
          active,
          canonicalRuntimeSymbols,
        ),
      ]);
    const properties = checker.getPropertiesOfType(type)
      .filter((property) => property.getName() !== "prototype" && publicDeclaration(property))
      .filter((property) => property.getDeclarations()?.some((declaration) =>
        declaredWithin(declaration, surfaceRoot)) ?? false)
      .flatMap((property) => {
        const declaration = property.valueDeclaration ?? property.getDeclarations()?.[0];
        if (declaration === undefined) {
          return [];
        }
        const propertyOwner = `${owner}.${property.getName()}`;
        const setters = property.getDeclarations()
          ?.filter(ts.isSetAccessorDeclaration)
          .flatMap((setter) => {
            const signature = checker.getSignatureFromDeclaration(setter);
            return signature === undefined
              ? []
              : signatureLeaves(checker, signature, propertyOwner, surfaceRoot);
          }) ?? [];
        return [
          ...setters,
          ...surfaceValueLeaves(
            checker,
            checker.getTypeOfSymbolAtLocation(property, declaration),
            propertyOwner,
            surfaceRoot,
            active,
            canonicalRuntimeSymbols,
          ),
        ];
      });
    const stringIndex = checker.getIndexTypeOfType(type, ts.IndexKind.String);
    const indexed = stringIndex === undefined
      ? []
      : surfaceValueLeaves(
        checker,
        stringIndex,
        `${owner}.*`,
        surfaceRoot,
        active,
        canonicalRuntimeSymbols,
      );
    return [...callable, ...constructable, ...properties, ...indexed];
  } finally {
    active.delete(type);
  }
};

export const deriveInboundNumberPaths = (entryDeclaration: string): readonly string[] => {
  const surfaceRoot = dirname(resolve(entryDeclaration));
  const program = ts.createProgram({
    rootNames: [entryDeclaration],
    options: {
      module: ts.ModuleKind.NodeNext,
      moduleResolution: ts.ModuleResolutionKind.NodeNext,
      target: ts.ScriptTarget.ES2022,
      skipLibCheck: false,
      strict: true,
    },
  });
  const diagnostics = ts.getPreEmitDiagnostics(program);
  if (diagnostics.length > 0) {
    throw new Error(ts.formatDiagnostics(diagnostics, {
      getCanonicalFileName: (name) => name,
      getCurrentDirectory: () => process.cwd(),
      getNewLine: () => "\n",
    }));
  }
  const source = program.getSourceFile(entryDeclaration);
  if (source === undefined) {
    throw new Error(`TypeScript did not load public declaration ${entryDeclaration}`);
  }
  const checker = program.getTypeChecker();
  const moduleSymbol = checker.getSymbolAtLocation(source);
  if (moduleSymbol === undefined) {
    throw new Error(`TypeScript did not resolve public module ${entryDeclaration}`);
  }

  const runtimeExports = checker.getExportsOfModule(moduleSymbol).flatMap((exported) => {
    const publicName = exported.getName();
    const symbol = (exported.flags & ts.SymbolFlags.Alias) === 0
      ? exported
      : checker.getAliasedSymbol(exported);
    // A type-only export is not itself a runtime entry point. Its leaves are reached transitively when an
    // exported function, class, or value accepts that type.
    const declaration = symbol.valueDeclaration;
    if (declaration === undefined) {
      return [];
    }
    return [{publicName, symbol, declaration}] as const;
  });
  const canonicalRuntimeSymbols = new Set(runtimeExports.map(({symbol}) => symbol));

  const leaves = runtimeExports.flatMap(({publicName, symbol, declaration}) => {
    const staticType = checker.getTypeOfSymbolAtLocation(symbol, declaration);
    const staticLeaves = surfaceValueLeaves(
      checker,
      staticType,
      publicName,
      surfaceRoot,
      new Set(),
      canonicalRuntimeSymbols,
      true,
    );
    if ((symbol.flags & ts.SymbolFlags.Class) === 0) {
      return staticLeaves;
    }
    return [
      ...staticLeaves,
      ...surfaceValueLeaves(
        checker,
        checker.getDeclaredTypeOfSymbol(symbol),
        publicName,
        surfaceRoot,
        new Set(),
        canonicalRuntimeSymbols,
        true,
      ),
    ];
  });
  return [...new Set(leaves)].sort();
};

export const assertBoundaryClassifications = (
  derived: readonly string[],
  classifications: Readonly<Record<string, BoundaryClassification>>,
): void => {
  const derivedSet = new Set(derived);
  const classifiedSet = new Set(Object.keys(classifications));
  const missing = derived.filter((path) => !classifiedSet.has(path));
  const stale = [...classifiedSet].filter((path) => !derivedSet.has(path)).sort();
  if (missing.length > 0 || stale.length > 0) {
    throw new Error(
      `packed TypeScript numeric boundary is not exactly classified` +
      `\nmissing: ${missing.join(", ") || "(none)"}` +
      `\nstale: ${stale.join(", ") || "(none)"}`,
    );
  }
};

export const assertHostileClassificationsExecuted = (
  classifications: Readonly<Record<string, BoundaryClassification>>,
  executed: readonly string[],
): void => {
  const required = Object.entries(classifications)
    .filter(([, classification]) => classification.disposition === "hostile-packed-test")
    .map(([path]) => path)
    .sort();
  const actual = [...new Set(executed)].sort();
  const actualSet = new Set(actual);
  const requiredSet = new Set(required);
  const missing = required.filter((path) => !actualSet.has(path));
  const stale = actual.filter((path) => !requiredSet.has(path));
  if (missing.length > 0 || stale.length > 0 || actual.length !== executed.length) {
    throw new Error(
      `packed TypeScript hostile boundary evidence is not exact` +
      `\nmissing: ${missing.join(", ") || "(none)"}` +
      `\nstale: ${stale.join(", ") || "(none)"}` +
      `\nduplicates: ${actual.length === executed.length ? "(none)" : "present"}`,
    );
  }
};

export const installedInterpreterDeclaration = (): string => {
  const javascript = fileURLToPath(import.meta.resolve("@mbackschat/a12-interpreter"));
  if (!javascript.endsWith(".js")) {
    throw new Error(`interpreter export did not resolve to JavaScript: ${javascript}`);
  }
  return `${javascript.slice(0, -3)}.d.ts`;
};
