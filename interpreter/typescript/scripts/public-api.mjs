import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, isAbsolute, relative } from "node:path";
import { writeFileSync } from "node:fs";

const compareCodeUnits = (left, right) => (left < right ? -1 : left > right ? 1 : 0);

const canonicalSymbol = (checker, ts, symbol) =>
  symbol && symbol.flags & ts.SymbolFlags.Alias
    ? checker.getAliasedSymbol(symbol)
    : symbol;

const isWithin = (root, file) => {
  const path = relative(root, file);
  return path !== "" && !path.startsWith("..") && !isAbsolute(path);
};

export async function renderPublicApi(typescriptPath, indexPath) {
  const ts = await import(pathToFileURL(typescriptPath).href);
  const program = ts.createProgram({
    rootNames: [indexPath],
    options: {
      module: ts.ModuleKind.NodeNext,
      moduleResolution: ts.ModuleResolutionKind.NodeNext,
      noEmit: true,
      target: ts.ScriptTarget.ES2022,
    },
  });
  const diagnostics = ts.getPreEmitDiagnostics(program);
  if (diagnostics.length > 0) {
    const host = {
      getCanonicalFileName: (fileName) => fileName,
      getCurrentDirectory: () => process.cwd(),
      getNewLine: () => "\n",
    };
    throw new Error(ts.formatDiagnosticsWithColorAndContext(diagnostics, host));
  }

  const source = program.getSourceFile(indexPath);
  const checker = program.getTypeChecker();
  const moduleSymbol = source && checker.getSymbolAtLocation(source);
  if (!source || !moduleSymbol) {
    throw new Error(`cannot resolve the public TypeScript module ${indexPath}`);
  }

  const exported = checker
    .getExportsOfModule(moduleSymbol)
    .sort((left, right) => compareCodeUnits(left.getName(), right.getName()));
  const exportedTargets = new Set(
    exported.map((symbol) => canonicalSymbol(checker, ts, symbol)),
  );
  const packageDeclarationRoot = dirname(indexPath);
  const hiddenReferences = new Set();
  const recordHiddenReference = (node) => {
    const referenced = canonicalSymbol(
      checker,
      ts,
      checker.getSymbolAtLocation(node),
    );
    if (
      !referenced ||
      (referenced.flags & ts.SymbolFlags.TypeParameter) !== 0 ||
      exportedTargets.has(referenced) ||
      !referenced.declarations?.some((declaration) =>
        isWithin(packageDeclarationRoot, declaration.getSourceFile().fileName),
      )
    ) {
      return;
    }
    hiddenReferences.add(referenced.getName());
  };
  const visitPublicDeclaration = (node) => {
    if (ts.isTypeReferenceNode(node)) {
      recordHiddenReference(node.typeName);
    } else if (ts.isExpressionWithTypeArguments(node)) {
      recordHiddenReference(node.expression);
    } else if (ts.isTypeQueryNode(node)) {
      recordHiddenReference(node.exprName);
    } else if (ts.isImportTypeNode(node) && node.qualifier) {
      recordHiddenReference(node.qualifier);
    }
    ts.forEachChild(node, visitPublicDeclaration);
  };
  exportedTargets.forEach((symbol) =>
    symbol.declarations?.forEach(visitPublicDeclaration),
  );
  if (hiddenReferences.size > 0) {
    throw new Error(
      `public exports reference package-local types that the root does not export: ${
        [...hiddenReferences].sort(compareCodeUnits).join(", ")
      }`,
    );
  }

  const printer = ts.createPrinter({ newLine: ts.NewLineKind.LineFeed });
  const declarations = exported.map((exportedSymbol) => {
    const target = canonicalSymbol(checker, ts, exportedSymbol);
    const nodes = target.declarations ?? [];
    if (nodes.length === 0) {
      throw new Error(`public export ${exportedSymbol.getName()} has no declaration`);
    }
    const rendered = nodes
      .map((node) =>
        printer.printNode(ts.EmitHint.Unspecified, node, node.getSourceFile()),
      )
      .join("\n");
    return `## ${exportedSymbol.getName()}\n${rendered}`;
  });

  return [
    "# @mbackschat/a12-interpreter public TypeScript ABI",
    "",
    ...declarations.flatMap((declaration) => [declaration, ""]),
  ].join("\n");
}

const invokedPath = process.argv[1] && fileURLToPath(import.meta.url);
if (invokedPath && invokedPath === process.argv[1]) {
  const [typescriptPath, indexPath, outputPath] = process.argv.slice(2);
  if (!typescriptPath || !indexPath || !outputPath) {
    throw new Error(
      "usage: public-api.mjs <typescript.js> <dist/index.d.ts> <output.api>",
    );
  }
  writeFileSync(
    outputPath,
    await renderPublicApi(typescriptPath, indexPath),
    "utf8",
  );
}
