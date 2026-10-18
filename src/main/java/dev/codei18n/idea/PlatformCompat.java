package dev.codei18n.idea;

import com.intellij.lang.CodeDocumentationAwareCommenter;
import com.intellij.psi.PsiComment;
import java.lang.reflect.Method;

/**
 * Wrappers for IntelliJ Platform APIs that differ between the supported builds (251 – 262).
 * Each method is looked up once; on builds without it a conservative fallback is used.
 */
final class PlatformCompat {
    /** Added in 262 (IJPL-162444); absent in 251–261. */
    private static final Method IS_DOC_LINE_COMMENT =
            find(CodeDocumentationAwareCommenter.class, "isDocumentationLineComment", PsiComment.class);
    private static final Method DOC_LINE_COMMENT_PREFIX =
            find(CodeDocumentationAwareCommenter.class, "getDocumentationLineCommentPrefix");
    private static final String FALLBACK_DOC_LINE_PREFIX = "///";

    private PlatformCompat() {}

    /** Whether {@code comment} is a line-style documentation comment such as {@code ///}. */
    static boolean isDocumentationLineComment(CodeDocumentationAwareCommenter commenter, PsiComment comment) {
        if (IS_DOC_LINE_COMMENT != null) {
            Object result = invoke(IS_DOC_LINE_COMMENT, commenter, comment);
            if (result instanceof Boolean value) return value;
        }
        return comment.getText().startsWith(FALLBACK_DOC_LINE_PREFIX);
    }

    /** The line documentation prefix, e.g. {@code ///}; never null. */
    static String documentationLineCommentPrefix(CodeDocumentationAwareCommenter commenter) {
        if (DOC_LINE_COMMENT_PREFIX != null) {
            Object result = invoke(DOC_LINE_COMMENT_PREFIX, commenter);
            if (result instanceof String value && !value.isEmpty()) return value;
        }
        return FALLBACK_DOC_LINE_PREFIX;
    }

    private static Method find(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }
}
