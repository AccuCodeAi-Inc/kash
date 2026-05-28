# Quirks

Weird challenges that have emerged

## Python: write-without-close doesn't flush

Agents may do something like:

```bash
python3 -c "open('/tmp/x','w').write('hello\n')"
cat /tmp/x
```

On the GraalPy engine the file handle is left open and never flushed. **This is actually correct behavior.**

The problem is that in CPython, it does work. AI has latched onto this incorrect form, and this bug
is present even in the latest models like opus 4.6.

So, we shim it. It's ugly. But it counters this.

This doesnt affect pydoite because its literally CPython (lol)
