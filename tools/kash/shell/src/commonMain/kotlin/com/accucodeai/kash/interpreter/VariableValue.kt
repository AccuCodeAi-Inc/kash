package com.accucodeai.kash.interpreter

/**
 * Value union for [Variable]. Mirrors a shell variable's value union:
 *
 *   - [Unset]    → declared (e.g. via `declare -a name`) but no element
 *                  ever written; renders as `declare -a name` with no `=`
 *                  in `declare -p`.
 *   - [Scalar]   → a plain string value.
 *   - [Indexed]  → an indexed array.
 *   - [Assoc]    → an associative array.
 *
 * Distinguishing [Unset] from `Indexed(empty)` / `Assoc(empty)` is what
 * lets `declare -p` print `declare -a x` vs `declare -a x=()` correctly
 * — bash uses the value-pointer null/non-null check; we use sealed
 * subtype.
 */
internal sealed interface VariableValue {
    /** Declared (with type attr) but never assigned. */
    object Unset : VariableValue

    data class Scalar(
        val s: String,
    ) : VariableValue

    class Indexed(
        val elements: MutableMap<Int, String> = mutableMapOf(),
    ) : VariableValue

    class Assoc(
        val elements: LinkedHashMap<String, String> = linkedMapOf(),
    ) : VariableValue

    /** Deep copy — element maps cloned so forks/snapshots are isolated. */
    fun copy(): VariableValue =
        when (this) {
            is Unset -> Unset
            is Scalar -> this
            is Indexed -> Indexed(elements.toMutableMap())
            is Assoc -> Assoc(LinkedHashMap(elements))
        }
}
