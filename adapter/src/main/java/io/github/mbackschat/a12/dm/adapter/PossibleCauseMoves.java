package io.github.mbackschat.a12.dm.adapter;

import java.util.List;
import java.util.Map;

import com.mgmtp.a12.kernel.md.model.a12internal.DocumentModel;
import com.mgmtp.a12.kernel.md.model.a12internal.IDocumentModelObjectReference;
import com.mgmtp.a12.kernel.md.model.a12internal.Rule;
import com.mgmtp.a12.kernel.md.model.a12internal.visitor.DocumentModelVisitor;
import com.mgmtp.a12.kernel.md.model.a12internal.visitor.DocumentModelWalker;
import com.mgmtp.a12.kernel.md.model.api.visitor.DocumentModelWalker.VisitProcess;
import com.mgmtp.a12.kernel.md.model.internal.service.utils.ElementUtils;

import io.github.mbackschat.a12.dm.core.EntityPath;
import io.github.mbackschat.a12.dm.core.PossibleCauseFilter;

/**
 * Carries a rule's possible-cause filter across a rename or move. A12's {@code MoveSupportDM} rewrites a rule's
 * condition, messages and error entity but not its filter (kernel 31.3.1, source-read), so an entry naming a moved
 * entity would stop matching the rewritten condition ({@code PossibleCauseFilterRewriteTest}).
 *
 * <p>An entry is relative to the rule's GROUP (KERNEL-FINDINGS KF682) and is rewritten, to the one spelling A12
 * admits, only when the move changes the path from the group to its entity; any other entry keeps its spelling, so a
 * move never turns an entry A12 refuses into one it admits.
 */
final class PossibleCauseMoves {

    private PossibleCauseMoves() {
    }

    static void apply(DocumentModel model, Map<String, String> entityMoves) {
        new DocumentModelWalker().acceptDocumentModel(model, new DocumentModelVisitor() {
            @Override
            public VisitProcess visitRule(Rule rule) {
                rule.getFilterForPossiblyCausingEntities().ifPresent(filter -> {
                    String group = EntityPath.of(ElementUtils.getPath(rule)).resolve("..").value();
                    move(filter.getEntitiesEnabledAsPossibleCauseIfFilled(), group, entityMoves);
                    move(filter.getEntitiesEnabledAsPossibleCauseIfNotFilled(), group, entityMoves);
                });
                return VisitProcess.CONTINUE_TRAVERSAL;
            }
        });
    }

    private static void move(List<? extends IDocumentModelObjectReference<?>> entries, String group,
            Map<String, String> entityMoves) {
        String movedGroup = moved(group, entityMoves);
        for (IDocumentModelObjectReference<?> entry : entries) {
            String entity = new PossibleCauseFilter(List.of(entry.getRelativePath()), List.of())
                    .entityPathsIfFilled(group).get(0);
            String before = admitted(group, entity);
            String after = admitted(movedGroup, moved(entity, entityMoves));
            if (!after.equals(before)) {
                entry.setRelativePath(after);
            }
        }
    }

    private static String admitted(String group, String entity) {
        return PossibleCauseFilter.ofEntities(group, List.of(entity), List.of()).entitiesIfFilled().get(0);
    }

    /** Where {@code path} ends up: the most specific move of it or of an ancestor, as A12's move support maps it. */
    private static String moved(String path, Map<String, String> entityMoves) {
        for (String prefix = path; !prefix.isEmpty(); prefix = prefix.substring(0, prefix.lastIndexOf('/'))) {
            String target = entityMoves.get(prefix);
            if (target != null) {
                return target + path.substring(prefix.length());
            }
        }
        return path;
    }
}
