-- Every table's row count and a digest of all its rows, one line per table: two databases with the
-- same lines hold the same data. Run as the superuser (row-level security would hide rows).
SELECT format('SELECT %L, count(*), md5(coalesce(string_agg(t::text, %L ORDER BY t::text), %L)) FROM %I t',
              c.relname, E'\n', '', c.relname)
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'r'
ORDER BY c.relname
\gexec
