// @vitest-environment node
// Real PGlite, in Node rather than jsdom: jsdom's Blob lacks the arrayBuffer() PGlite needs.
import { openSession, type Session } from "./sqlSession";

const fixture = {
  schema: "create table payments (id int, status text); create table settlements (payment_id int);",
  seed: "insert into payments values (1, 'captured'), (2, 'captured'), (3, 'failed'), (4, 'captured');"
    + " insert into settlements values (1);",
  reference: "select id from payments p where status = 'captured'"
    + " and not exists (select 1 from settlements s where s.payment_id = p.id) order by id",
  orderMatters: true,
};

let session: Session;
beforeAll(async () => { session = await openSession(fixture); });
afterAll(() => session.close());

test("a different correct query is the answer", async () => {
  const out = await session.run("select p.id from payments p left join settlements s on s.payment_id = p.id"
    + " where s.payment_id is null and p.status = 'captured' order by 1");
  expect(out.verdict).toEqual({ kind: "right" });
  expect(out.columns).toEqual(["id"]);
});

test("a wrong query says how it differs", async () => {
  expect((await session.run("select id from payments where status = 'captured'")).verdict)
    .toEqual({ kind: "row-count", got: 3, want: 2 });
  expect((await session.run("select id from payments where id in (2, 4) order by id desc")).verdict)
    .toEqual({ kind: "wrong-order" });
});

test("changes are rolled back, so the next try sees the original data", async () => {
  const out = await session.run("delete from settlements; select count(*) from settlements");
  expect(out.rows).toEqual([[0]]);
  expect((await session.run("select count(*) from settlements")).rows).toEqual([[1]]);
});

test("a SQL error is thrown with Postgres's message, and the session still works after it", async () => {
  await expect(session.run("select nope from payments")).rejects.toThrow(/nope/);
  expect((await session.run("select 1")).rows).toEqual([[1]]);
});
